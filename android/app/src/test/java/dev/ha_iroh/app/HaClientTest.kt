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

class HaClientTest {
    private val server = MockWebServer()
    private val received = mutableListOf<String>()

    private fun start(refresh: MockResponse, authOk: Boolean = true) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/auth/token" -> { received += request.body!!.utf8(); refresh }
                "/api/websocket" -> MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"auth_required","ha_version":"2026.1"}""")
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received += text
                        webSocket.send(if (authOk) """{"type":"auth_ok"}""" else """{"type":"auth_invalid","message":"nope"}""")
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
}
