package dev.ha_iroh.app

import kotlinx.coroutines.Dispatchers
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

class Session internal constructor(private val ws: WebSocket, private val inbox: Channel<JsonObject>) {
    private var nextId = 1

    /** Sends a command and returns its `result` message. ponytail: drops events; 07 adds subscriptions. */
    suspend fun sendCommand(cmd: JsonObject): JsonObject {
        val id = nextId++
        ws.send(JsonObject(cmd + ("id" to JsonPrimitive(id))).toString())
        return withTimeout(TIMEOUT_MS) {
            var m: JsonObject
            do { m = inbox.receive() } while (m["id"]?.jsonPrimitive?.intOrNull != id)
            m
        }
    }

    fun close() { ws.close(1000, null) }
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

    suspend fun refresh(): String = try {
        http.token(baseUrl, "grant_type" to "refresh_token", "refresh_token" to refreshToken).str("access_token")
    } catch (e: TokenError) {
        if (e.status == 401 || e.error == "invalid_grant") throw ReloginRequired() else throw e
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
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { inbox.close() }
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
