package dev.ha_iroh.app

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

class HaClientTest {
    private val server = MockWebServer()
    private val received = mutableListOf<String>()

    private fun start(refresh: MockResponse, authOk: Boolean = true, onCommand: (WebSocket, kotlinx.serialization.json.JsonObject) -> Unit = { _, _ -> }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/auth/token" -> { received += request.body!!.utf8(); refresh }
                "/api/websocket" -> MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"auth_required","ha_version":"2026.1"}""")
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received += text
                        val m = json.parseToJsonElement(text).jsonObject
                        if (m["type"]?.jsonPrimitive?.content == "auth")
                            webSocket.send(if (authOk) """{"type":"auth_ok"}""" else """{"type":"auth_invalid","message":"nope"}""")
                        else onCommand(webSocket, m)
                    }
                }).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    private fun client() = HaClient(OkHttpClient(), server.url("/").toString().trimEnd('/'), "ref1")
    private val tokens = MockResponse.Builder().code(200).body("""{"access_token":"acc1","token_type":"Bearer","expires_in":1800}""").build()

    @After fun stop() = server.close()

    @Test fun accessTokenIsCachedUntilForced() = runBlocking {
        start(tokens)
        val c = client()
        val (tok, exp) = c.accessToken()
        assertEquals("acc1", tok)
        assertTrue(exp in 1700..1800)
        c.accessToken()
        assertEquals(1, received.size) // cached
        c.accessToken(force = true)
        assertEquals(2, received.size)
    }

    @Test fun refreshThenWsAuthHandshake() = runBlocking {
        start(tokens)
        val c = client()
        c.connect()
        assertTrue(received[0].contains("grant_type=refresh_token") && received[0].contains("refresh_token=ref1"))
        assertEquals("""{"type":"auth","access_token":"acc1"}""", received[1])
        c.close()
    }

    @Test fun invalidGrantMeansRelogin() = runBlocking {
        start(MockResponse.Builder().code(400).body("""{"error":"invalid_grant"}""").build())
        assertThrows(ReloginRequired::class.java) { runBlocking { client().connect() } }
        Unit
    }

    @Test fun authInvalidMeansRelogin() = runBlocking {
        start(tokens, authOk = false)
        assertThrows(ReloginRequired::class.java) { runBlocking { client().connect() } }
        Unit
    }

    @Test fun subscribeYieldsEventsWhileCommandGetsResult() = runBlocking {
        start(tokens) { ws, m ->
            val id = m["id"]!!.jsonPrimitive.int
            when (m["type"]!!.jsonPrimitive.content) {
                "subscribe_entities" -> {
                    ws.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                    ws.send("""{"id":$id,"type":"event","event":{"a":{"x":1}}}""")
                }
                "test" -> {
                    ws.send("""{"id":$id,"type":"result","success":true,"result":"pong"}""")
                    ws.send("""{"id":1,"type":"event","event":{"c":{"x":2}}}""")
                }
                "unsubscribe_events" -> {
                    received += "unsub:" + m["subscription"]!!.jsonPrimitive.int
                    ws.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                }
                "fail" -> ws.send("""{"id":$id,"type":"result","success":false,"error":{"code":"nope","message":"bad"}}""")
            }
        }
        val c = client()
        val s = c.connect()
        val events = async { s.subscribe(buildJsonObject { put("type", "subscribe_entities") }).take(2).toList() }
        // first subscribe event proves registration; then command triggers second event
        while (received.none { it.contains("subscribe_entities") }) kotlinx.coroutines.delay(10)
        val r = s.sendCommand(buildJsonObject { put("type", "test") })
        assertEquals("pong", r["result"]!!.jsonPrimitive.content)
        assertEquals(listOf("""{"a":{"x":1}}""", """{"c":{"x":2}}"""), events.await().map { it.toString() })
        assertEquals("unsub:1", received.last { it.startsWith("unsub:") })
        val e = assertThrows(HaError::class.java) { runBlocking { s.sendCommand(buildJsonObject { put("type", "fail") }) } }
        assertEquals("nope", e.code)
        c.close()
    }

    @Test fun remoteCloseFailsSubscription() = runBlocking {
        start(tokens) { ws, m ->
            if (m["type"]!!.jsonPrimitive.content == "drop") {
                ws.send("""{"id":${m["id"]!!.jsonPrimitive.int},"type":"result","success":true,"result":null}""")
                ws.close(1001, "bye")
            }
        }
        val c = client()
        val s = c.connect()
        assertThrows(java.io.IOException::class.java) { runBlocking { s.subscribe(buildJsonObject { put("type", "drop") }).toList() } }
        assertThrows(java.io.IOException::class.java) { runBlocking { s.sendCommand(buildJsonObject { put("type", "x") }) } }
        c.close()
    }
}
