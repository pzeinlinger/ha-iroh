package dev.ha_iroh.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// Official app's values; see SPEC §11 risk 2 for the decision record.
const val CLIENT_ID = "https://home-assistant.io/android"
const val REDIRECT_URI = "homeassistant://auth-callback"
const val BASE_URL = "http://ha.iroh"
private const val TIMEOUT_MS = 15_000L

internal val json = Json { ignoreUnknownKeys = true }

/** Async OkHttp call that cancels with the coroutine; returns (status, body). */
internal suspend fun OkHttpClient.call(req: Request): Pair<Int, String> = suspendCancellableCoroutine { c ->
    val call = newCall(req)
    c.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = c.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) =
            c.resumeWith(runCatching { response.use { it.code to it.body.string() } })
    })
}

class TokenError(val status: Int, val error: String?) : Exception("token endpoint: HTTP $status ${error ?: ""}") // never the body

/** POST /auth/token with [fields] + client_id; shared by refresh and code exchange. */
internal suspend fun OkHttpClient.token(baseUrl: String, vararg fields: Pair<String, String>): JsonObject {
    val form = FormBody.Builder().apply { fields.forEach { add(it.first, it.second) } }.add("client_id", CLIENT_ID).build()
    val (status, body) = call(Request.Builder().url("$baseUrl/auth/token").post(form).build())
    val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    if (status != 200) throw TokenError(status, o?.get("error")?.jsonPrimitive?.contentOrNull)
    return o ?: throw TokenError(status, "bad_json")
}

internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: error("token response missing $k")

/** Refresh token rejected (revoked / unknown) or WS auth_invalid. */
class ReloginRequired : Exception("re-login required")

/** `result` with success=false. */
class HaError(val code: String?, message: String) : Exception(message)

/** Reader loop dispatches by message id: `result` -> pending command, `event` -> subscription. */
class Session internal constructor(private val ws: WebSocket, inbox: ReceiveChannel<JsonObject>) {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val subscriptions = ConcurrentHashMap<Int, SendChannel<JsonObject>>()
    private val reader = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        var cause: Throwable? = null
        try {
            for (m in inbox) {
                val id = m["id"]?.jsonPrimitive?.intOrNull ?: continue
                when (m["type"]?.jsonPrimitive?.content) {
                    "result" -> pending.remove(id)?.complete(m)
                    "event" -> subscriptions[id]?.trySend(m["event"]!!.jsonObject)
                }
            }
        } catch (e: CancellationException) { // close()
            cause = IOException("session closed")
        } catch (e: Throwable) { cause = e } finally {
            val err = cause ?: IOException("websocket closed")
            pending.values.forEach { it.completeExceptionally(err) }
            subscriptions.values.forEach { it.close(err) }
        }
    }

    private suspend fun request(cmd: JsonObject, id: Int): JsonObject {
        val d = CompletableDeferred<JsonObject>().also { pending[id] = it }
        try {
            // registered first, so a reader that finishes later still fails [d]; one that already finished is caught here
            if (!reader.isActive) throw IOException("session closed")
            if (!ws.send(JsonObject(cmd + ("id" to JsonPrimitive(id))).toString())) throw IOException("websocket send failed")
            val m = withTimeout(TIMEOUT_MS) { d.await() }
            if (m["success"]?.jsonPrimitive?.booleanOrNull == false) {
                val e = m["error"]?.jsonObject
                throw HaError(e?.get("code")?.jsonPrimitive?.contentOrNull, e?.get("message")?.jsonPrimitive?.contentOrNull ?: "command failed")
            }
            return m
        } finally { pending.remove(id) }
    }

    /** Sends a command and returns its `result` message; throws [HaError] on success=false. */
    suspend fun sendCommand(cmd: JsonObject): JsonObject = request(cmd, nextId.getAndIncrement())

    /** Emits each `event` payload of the subscription; unsubscribes on cancel. */
    fun subscribe(cmd: JsonObject): Flow<JsonObject> = callbackFlow {
        val id = nextId.getAndIncrement()
        subscriptions[id] = channel
        try { request(cmd, id) } catch (e: Throwable) { subscriptions.remove(id); throw e }
        awaitClose {
            subscriptions.remove(id)
            ws.send(buildJsonObject { put("id", nextId.getAndIncrement()); put("type", "unsubscribe_events"); put("subscription", id) }.toString())
        }
    }.buffer(Channel.UNLIMITED)

    fun close() {
        ws.close(1000, null)
        reader.cancel() // finally block fails pending + closes subscriptions
    }
}

/**
 * One per server. [onClose] releases the underlying tunnel.
 * Production: tunnel.okHttpClient() + BASE_URL; tests: MockWebServer.
 */
class HaClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val refreshToken: String,
    private val onClose: () -> Unit = {},
) {
    private var session: Session? = null

    private suspend fun refreshTokens(): JsonObject = try {
        http.token(baseUrl, "grant_type" to "refresh_token", "refresh_token" to refreshToken)
    } catch (e: TokenError) {
        if (e.status == 401 || e.error == "invalid_grant") throw ReloginRequired() else throw e
    }

    suspend fun refresh(): String = refreshTokens().str("access_token")

    private var cached: Pair<String, Long>? = null // access token, expiry (epoch ms)

    /** Cached access token + seconds left (for the WebView's external_auth); refreshes if <30 s left or [force]. */
    suspend fun accessToken(force: Boolean = false): Pair<String, Int> {
        val now = System.currentTimeMillis()
        val (token, exp) = cached?.takeIf { !force && it.second - now > 30_000 } ?: refreshTokens().let {
            (it.str("access_token") to now + (it["expires_in"]?.jsonPrimitive?.longOrNull ?: 1800) * 1000).also { c -> cached = c }
        }
        return token to ((exp - now) / 1000).toInt()
    }

    suspend fun get(path: String): String {
        val token = refresh()
        val (status, body) = http.call(Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token").build())
        if (status == 401) throw ReloginRequired()
        return "$status\n$body"
    }

    suspend fun connect(): Session {
        session?.close()
        val token = refresh()
        val inbox = Channel<JsonObject>(Channel.UNLIMITED)
        val ws = withContext(Dispatchers.IO) {
            http.newWebSocket(Request.Builder().url("$baseUrl/api/websocket").build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try { inbox.trySend(json.parseToJsonElement(text).jsonObject) } catch (e: Exception) { inbox.close(e) }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { inbox.close(IOException("websocket closed: $code $reason")) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { inbox.close(t) }
            })
        }
        try {
            withTimeout(TIMEOUT_MS) {
                val first = inbox.receive()["type"]?.jsonPrimitive?.content
                check(first == "auth_required") { "expected auth_required, got $first" }
                ws.send(buildJsonObject { put("type", "auth"); put("access_token", token) }.toString())
                when (val t = inbox.receive()["type"]?.jsonPrimitive?.content) {
                    "auth_ok" -> {}
                    "auth_invalid" -> throw ReloginRequired()
                    else -> error("unexpected $t")
                }
            }
        } catch (e: Throwable) {
            ws.cancel()
            throw e
        }
        return Session(ws, inbox).also { session = it }
    }

    fun close() {
        session?.close()
        session = null
        onClose()
    }
}
