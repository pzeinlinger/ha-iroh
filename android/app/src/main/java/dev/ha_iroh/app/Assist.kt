package dev.ha_iroh.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

data class AssistReply(val speech: String, val conversationId: String?)

/** Pipeline `error` event. */
class AssistError(val code: String?, message: String) : Exception(message)

private const val ASK_TIMEOUT_MS = 30_000L
private val DONE = setOf("intent-end", "error", "run-end")

/** Text Assist over an authenticated [Session]. */
class AssistSession(private val session: Session) {
    suspend fun ask(text: String, conversationId: String?, pipelineId: String?): Result<AssistReply> = runCatching {
        val cmd = buildJsonObject {
            put("type", "assist_pipeline/run")
            put("start_stage", "intent")
            put("end_stage", "intent")
            put("input", buildJsonObject { put("text", text) })
            pipelineId?.let { put("pipeline", it) }
            conversationId?.let { put("conversation_id", it) }
        }
        withTimeoutOrNull(ASK_TIMEOUT_MS) {
            // first() cancels the flow, which unsubscribes
            val ev = session.subscribe(cmd).first { it.type() in DONE }
            val data = ev["data"]?.jsonObject
            when (ev.type()) {
                "error" -> throw AssistError(data?.get("code")?.jsonPrimitive?.contentOrNull, data?.get("message")?.jsonPrimitive?.contentOrNull ?: "pipeline error")
                "run-end" -> throw AssistError("no-intent", "pipeline ended without a reply")
                else -> {
                    val out = data?.get("intent_output")?.jsonObject
                    AssistReply(
                        out?.get("response")?.jsonObject?.get("speech")?.jsonObject?.get("plain")?.jsonObject?.get("speech")?.jsonPrimitive?.contentOrNull ?: "",
                        out?.get("conversation_id")?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
        } ?: throw AssistError("timeout", "timed out")
    }

    private fun JsonObject.type() = this["type"]?.jsonPrimitive?.contentOrNull
}

data class Pipeline(val id: String, val name: String, val preferred: Boolean)

/** Parses an `assist_pipeline/pipeline/list` result message. */
fun parsePipelines(msg: JsonObject): List<Pipeline> {
    val r = msg["result"]?.jsonObject ?: return emptyList()
    val pref = r["preferred_pipeline"]?.jsonPrimitive?.contentOrNull
    return r["pipelines"]?.jsonArray?.mapNotNull {
        val o = it.jsonObject
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        Pipeline(id, o["name"]?.jsonPrimitive?.contentOrNull ?: id, id == pref)
    } ?: emptyList()
}
