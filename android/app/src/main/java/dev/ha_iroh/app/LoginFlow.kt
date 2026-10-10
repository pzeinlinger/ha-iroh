package dev.ha_iroh.app

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class Field(val name: String, val type: String, val required: Boolean)
sealed interface FlowResult
data class Step(val flowId: String, val stepId: String, val fields: List<Field>, val errors: List<String>) : FlowResult
data class Done(val code: String) : FlowResult
data class Tokens(val access: String, val refresh: String)

class LoginFlow(private val http: OkHttpClient, private val baseUrl: String = BASE_URL) {
    suspend fun start(): Step = post(
        "/auth/login_flow",
        buildJsonObject {
            put("client_id", CLIENT_ID)
            putJsonArray("handler") { add("homeassistant"); add(JsonNull) }
            put("redirect_uri", REDIRECT_URI)
        },
    ) as? Step ?: error("login_flow ended before the first step")

    suspend fun submit(step: Step, values: Map<String, String>): FlowResult = post(
        "/auth/login_flow/${step.flowId}",
        buildJsonObject {
            values.forEach { (k, v) -> put(k, v) }
            put("client_id", CLIENT_ID)
        },
    )

    suspend fun exchange(code: String): Tokens {
        val o = http.token(baseUrl, "grant_type" to "authorization_code", "code" to code)
        return Tokens(o.str("access_token"), o.str("refresh_token"))
    }

    private suspend fun post(path: String, body: JsonObject): FlowResult {
        val req = Request.Builder().url(baseUrl + path).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val (status, text) = http.call(req)
        check(status == 200) { "login_flow failed: $status $text" }
        val o = json.parseToJsonElement(text).jsonObject
        return when (val type = o["type"]?.jsonPrimitive?.content) {
            "create_entry" -> Done(o["result"]!!.jsonPrimitive.content)
            "abort" -> error("login aborted: ${o["reason"]?.jsonPrimitive?.content}")
            "form" -> Step(
                o["flow_id"]!!.jsonPrimitive.content,
                o["step_id"]?.jsonPrimitive?.content ?: "",
                (o["data_schema"] as? JsonArray).orEmpty().map {
                    val f = it.jsonObject
                    Field(f["name"]!!.jsonPrimitive.content, f["type"]?.jsonPrimitive?.content ?: "string", f["required"]?.jsonPrimitive?.boolean ?: false)
                },
                (o["errors"] as? JsonObject)?.values?.map { it.jsonPrimitive.content }.orEmpty(),
            )
            else -> error("unsupported login_flow result: $type")
        }
    }
}
