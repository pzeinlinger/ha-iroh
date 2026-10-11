package dev.ha_iroh.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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

class AssistTest {
    private val server = MockWebServer()
    private val runs = mutableListOf<JsonObject>()

    private fun session(fail: Boolean = false, events: (Int) -> List<String>): Pair<HaClient, Session> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/auth/token" -> MockResponse.Builder().body("""{"access_token":"a"}""").build()
                else -> MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"auth_required"}""") }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val m = json.parseToJsonElement(text).jsonObject
                        val id = m["id"]?.jsonPrimitive?.int
                        when (m["type"]?.jsonPrimitive?.content) {
                            "auth" -> webSocket.send("""{"type":"auth_ok"}""")
                            "assist_pipeline/run" -> {
                                runs += m
                                if (fail) webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"pipeline-not-found","message":"nope"}}""")
                                else {
                                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                                    events(id!!).forEach(webSocket::send)
                                }
                            }
                        }
                    }
                }).build()
            }
        }
        server.start()
        val c = HaClient(OkHttpClient(), server.url("/").toString().trimEnd('/'), "r")
        return c to runBlocking { c.connect() }
    }

    @After fun stop() = server.close()

    @Test fun happyPathYieldsSpeechAndConversationId() = runBlocking {
        val (c, s) = session { id -> listOf(
            """{"id":$id,"type":"event","event":{"type":"run-start","data":{}}}""",
            """{"id":$id,"type":"event","event":{"type":"intent-start","data":{}}}""",
            """{"id":$id,"type":"event","event":{"type":"intent-end","data":{"intent_output":{"conversation_id":"c1","response":{"speech":{"plain":{"speech":"Turned on"}}}}}}}""",
        ) }
        val r = AssistSession(s).ask("turn on bed light", "c0", "p1").getOrThrow()
        assertEquals(AssistReply("Turned on", "c1"), r)
        val run = runs.single()
        assertEquals("intent", run["start_stage"]!!.jsonPrimitive.content)
        assertEquals("turn on bed light", run["input"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("c0", run["conversation_id"]!!.jsonPrimitive.content)
        assertEquals("p1", run["pipeline"]!!.jsonPrimitive.content)
        c.close()
    }

    @Test fun errorEventBecomesAssistError() = runBlocking {
        val (c, s) = session { id -> listOf(
            """{"id":$id,"type":"event","event":{"type":"run-start","data":{}}}""",
            """{"id":$id,"type":"event","event":{"type":"error","data":{"code":"intent-failed","message":"boom"}}}""",
        ) }
        val e = AssistSession(s).ask("x", null, null).exceptionOrNull() as AssistError
        assertEquals("intent-failed", e.code)
        assertEquals("boom", e.message)
        assertNull(runs.single()["conversation_id"])
        c.close()
    }

    @Test fun failedResultSurfacesHaError() = runBlocking {
        val (c, s) = session(fail = true) { emptyList() }
        val e = AssistSession(s).ask("x", null, "bad").exceptionOrNull() as HaError
        assertEquals("pipeline-not-found", e.code)
        c.close()
    }

    @Test fun secondAskSendsConversationIdFromFirstReply() = runBlocking {
        val (c, s) = session { id -> listOf(
            """{"id":$id,"type":"event","event":{"type":"intent-end","data":{"intent_output":{"conversation_id":"c1","response":{"speech":{"plain":{"speech":"ok"}}}}}}}""",
        ) }
        val a = AssistSession(s)
        val first = a.ask("one", null, null).getOrThrow()
        a.ask("two", first.conversationId, null).getOrThrow()
        assertNull(runs[0]["conversation_id"])
        assertEquals("c1", runs[1]["conversation_id"]!!.jsonPrimitive.content)
        c.close()
    }

    @Test fun `parses pipeline list and relay urls`() {
        val m = json.parseToJsonElement("""{"result":{"preferred_pipeline":"b","pipelines":[{"id":"a","name":"A"},{"id":"b","name":"B"}]}}""").jsonObject
        assertEquals(listOf(Pipeline("a", "A", false), Pipeline("b", "B", true)), parsePipelines(m))
        assertEquals(listOf("https://x", "https://y"), parseRelayUrls(" https://x,\n\nhttps://y "))
        assertTrue(parseRelayUrls(" ,\n").isEmpty())
    }
}
