package dev.ha_iroh.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LoginFlowTest {
    private val server = MockWebServer()
    private val seen = mutableListOf<String>() // "PATH BODY"
    private var submits = 0

    private fun json(s: String) = MockResponse.Builder().code(200).body(s).build()

    private fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = request.body?.utf8() ?: ""
                seen += "$path $body"
                return when (path) {
                    "/auth/login_flow" -> json(
                        """{"type":"form","flow_id":"f1","step_id":"init","errors":{},
                        "data_schema":[{"type":"string","name":"username","required":true},{"type":"string","name":"password","required":true}]}"""
                    )
                    "/auth/login_flow/f1" -> when (submits++) {
                        0 -> json("""{"type":"form","flow_id":"f1","step_id":"mfa","errors":{},"data_schema":[{"name":"code","type":"string","required":true}]}""")
                        else -> json("""{"type":"create_entry","flow_id":"f1","result":"authcode"}""")
                    }
                    "/auth/token" -> json("""{"access_token":"acc","refresh_token":"ref","token_type":"Bearer","expires_in":1800}""")
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    @Test fun loginWithMfaThenTokenExchange() = runBlocking {
        start()
        val flow = LoginFlow(OkHttpClient(), server.url("/").toString().trimEnd('/'))

        val init = flow.start()
        assertEquals(listOf("username", "password"), init.fields.map { it.name })
        assertTrue(init.fields.all { it.required })
        val start = Json.parseToJsonElement(seen[0].substringAfter(' ')).jsonObject
        assertEquals(CLIENT_ID, start["client_id"]!!.jsonPrimitive.content)
        assertEquals(REDIRECT_URI, start["redirect_uri"]!!.jsonPrimitive.content)

        val mfa = flow.submit(init, mapOf("username" to "u", "password" to "p")) as Step
        assertEquals("mfa", mfa.stepId)
        assertEquals(listOf("code"), mfa.fields.map { it.name })

        val done = flow.submit(mfa, mapOf("code" to "123456"))
        assertEquals(Done("authcode"), done)

        val tokens = flow.exchange("authcode")
        assertEquals(Tokens("acc", "ref"), tokens)
        assertTrue(seen.last().contains("grant_type=authorization_code") && seen.last().contains("code=authcode"))
    }
}
