package dev.ha_iroh.app

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

data class AssistReply(val speech: String, val conversationId: String?)

/** Pipeline `error` event. */
class AssistError(val code: String?, message: String) : Exception(message)

private const val ASK_TIMEOUT_MS = 30_000L
private const val LISTEN_TIMEOUT_MS = 60_000L
private val DONE = setOf("intent-end", "error", "run-end")

sealed interface VoiceEvent {
    data object Listening : VoiceEvent
    data object VadEnd : VoiceEvent
    data class Transcript(val text: String) : VoiceEvent
    data class Reply(val speech: String, val conversationId: String?) : VoiceEvent
    data class Tts(val url: String) : VoiceEvent
    data class Error(val code: String?, val message: String) : VoiceEvent
    data object Done : VoiceEvent
}

/** STT binary frame: handler id byte + PCM16LE; no [pcm] = end of stream. */
fun sttFrame(handlerId: Int, pcm: ByteArray = ByteArray(0)): ByteArray = byteArrayOf(handlerId.toByte()) + pcm

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
            val data = ev["data"] as? JsonObject
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

    /**
     * Voice run: forwards [audio] chunks as STT frames after `run-start`; closing [audio] ends the speech
     * (we close it on `stt-vad-end`). Flow completes on run-end/error/timeout.
     */
    fun listen(audio: Channel<ByteArray>, conversationId: String?, pipelineId: String?): Flow<VoiceEvent> = flow {
        val cmd = buildJsonObject {
            put("type", "assist_pipeline/run")
            put("start_stage", "stt")
            put("end_stage", "tts")
            put("input", buildJsonObject { put("sample_rate", 16000) })
            pipelineId?.let { put("pipeline", it) }
            conversationId?.let { put("conversation_id", it) }
        }
        var sender: Job? = null
        try {
            withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
                    emitAll(session.subscribe(cmd).transformWhile { ev ->
                        val d = ev["data"] as? JsonObject // null for run-end
                        when (ev.type()) {
                            "run-start" -> {
                                val id = d?.get("runner_data")?.jsonObject?.get("stt_binary_handler_id")?.jsonPrimitive?.intOrNull
                                    ?: throw AssistError("no-handler", "no stt handler id")
                                emit(VoiceEvent.Listening)
                                sender = launch {
                                    for (c in audio) session.sendBinary(sttFrame(id, c))
                                    session.sendBinary(sttFrame(id))
                                }
                            }
                            "stt-vad-end" -> { audio.close(); emit(VoiceEvent.VadEnd) }
                            "stt-end" -> emit(VoiceEvent.Transcript(d?.get("stt_output")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""))
                            "intent-end" -> {
                                val out = d?.get("intent_output")?.jsonObject
                                emit(VoiceEvent.Reply(
                                    out?.get("response")?.jsonObject?.get("speech")?.jsonObject?.get("plain")?.jsonObject?.get("speech")?.jsonPrimitive?.contentOrNull ?: "",
                                    out?.get("conversation_id")?.jsonPrimitive?.contentOrNull,
                                ))
                            }
                            "tts-end" -> d?.get("tts_output")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull?.let { emit(VoiceEvent.Tts(it)) }
                            "error" -> emit(VoiceEvent.Error(d?.get("code")?.jsonPrimitive?.contentOrNull, d?.get("message")?.jsonPrimitive?.contentOrNull ?: "pipeline error"))
                            "run-end" -> emit(VoiceEvent.Done)
                        }
                        ev.type() != "error" && ev.type() != "run-end"
                    })
                    sender?.cancel() // run ended before the user stopped talking
            } ?: emit(VoiceEvent.Error("timeout", "timed out"))
        } finally { audio.close() }
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
