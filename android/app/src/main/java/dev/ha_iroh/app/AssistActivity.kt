package dev.ha_iroh.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

const val EXTRA_SERVER_ID = "serverId"
const val EXTRA_VOICE = "voice"

private enum class Kind { USER, REPLY, ERROR }
private data class Line(val text: String, val kind: Kind)

/** Translucent bottom sheet; one tunnel + session per chosen server, released in [onStop] (no warm keeping). */
class AssistActivity : ComponentActivity() {
    private val app get() = application as App
    private var job: Job? = null // connect + ask for the current server
    private var activeId: String? = null // server whose tunnel may be open
    private var audio: Channel<ByteArray>? = null // closing it = end of speech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extraId = intent.getStringExtra(EXTRA_SERVER_ID)
        val voice = intent.getBooleanExtra(EXTRA_VOICE, false) || intent.component?.className?.endsWith(".AssistVoice") == true
        setContent { MaterialTheme { Sheet(extraId, voice) } }
    }

    // ponytail: no state restore; coming back from background re-opens a blank sheet on the first send
    override fun onStop() {
        job?.cancel()
        release()
        super.onStop()
        finish()
    }

    /** Releases the tunnel of [activeId] off the main thread. */
    private fun release() {
        val id = activeId ?: return
        activeId = null
        CoroutineScope(Dispatchers.IO).launch { app.manager.release(id) }
    }

    /** Mic -> [ch] in ~2048-byte chunks until [ch] is closed; closes [ch] on exit. */
    private fun record(ch: Channel<ByteArray>) {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        try {
            r.startRecording()
            val buf = ByteArray(2048)
            while (!ch.isClosedForSend) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) break
                ch.trySend(buf.copyOf(n))
            }
        } finally { r.release(); ch.close() }
    }

    /** Fetches HA's TTS clip over the tunnel and plays it to the end. */
    private suspend fun play(c: HaClient, url: String) {
        val bytes = c.bytes(url)
        val f = withContext(Dispatchers.IO) { File.createTempFile("tts", ".mp3", cacheDir).also { it.writeBytes(bytes) } }
        val mp = MediaPlayer()
        try {
            val done = CompletableDeferred<Unit>()
            mp.setOnCompletionListener { done.complete(Unit) }
            mp.setOnErrorListener { _, w, e -> done.completeExceptionally(AssistError("tts", "playback error $w/$e")); true }
            withContext(Dispatchers.IO) { mp.setDataSource(f.path); mp.prepare() }
            mp.start()
            done.await()
        } finally { mp.release(); withContext(NonCancellable) { withContext(Dispatchers.IO) { f.delete() } } }
    }

    @Composable
    private fun Sheet(extraId: String?, voice: Boolean) {
        val servers by app.store.servers.collectAsState()
        LaunchedEffect(Unit) { app.store.init() }
        var chosen by remember { mutableStateOf(extraId) }
        val server = servers.firstOrNull { it.id == chosen } ?: servers.firstOrNull { it.isDefaultAssist } ?: servers.firstOrNull()
        val lines = remember { mutableStateListOf<Line>() }
        var convId by remember { mutableStateOf<String?>(null) }
        var assist by remember { mutableStateOf<Pair<HaClient, AssistSession>?>(null) } // one client per sheet session (client() opens a tunnel per call)
        var busy by remember { mutableStateOf(false) }
        var listening by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) } // voice progress, so a slow HA (cold STT model) doesn't look like nothing happening
        var text by remember { mutableStateOf("") }
        var menu by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

        suspend fun connected(s: Server) = assist ?: run {
            activeId = s.id // set before connecting so a stop mid-connect still releases s.id
            val c = app.manager.client(s)
            (c to AssistSession(c.connect())).also { assist = it }
        }

        fun send() {
            val s = server ?: return
            val msg = text.trim().ifEmpty { return }
            text = ""; busy = true; lines += Line(msg, Kind.USER)
            job = scope.launch {
                try {
                    val r = connected(s).second.ask(msg, convId, s.pipelineId).getOrThrow()
                    convId = r.conversationId ?: convId
                    lines += Line(r.speech.ifEmpty { "(no reply)" }, Kind.REPLY)
                } catch (e: CancellationException) {
                    release() // stopped or server switched while in flight; no-op if already released
                    throw e
                } catch (e: Exception) {
                    Log.w("Assist", "ask failed", e)
                    lines += Line(e.message ?: e.toString(), Kind.ERROR)
                    if (e !is AssistError && e !is HaError) { release(); assist = null } // dead session: reconnect next time
                } finally { busy = false }
            }
        }

        fun listen() {
            val s = server ?: return
            busy = true; listening = true; status = "connecting…"
            val ch = Channel<ByteArray>(Channel.UNLIMITED).also { audio = it }
            job = scope.launch {
                try {
                    launch(Dispatchers.IO) { runCatching { record(ch) }.onFailure { Log.w("Assist", "mic", it) } } // capture while the tunnel connects; chunks buffer in ch
                    val (client, a) = connected(s)
                    var tts: String? = null
                    a.listen(ch, convId, s.pipelineId).collect { ev ->
                        when (ev) {
                            is VoiceEvent.Transcript -> { lines += Line(ev.text.ifEmpty { "(nothing heard)" }, Kind.USER); listening = false; status = "thinking…" } // not every pipeline sends stt-vad-end
                            is VoiceEvent.Reply -> { convId = ev.conversationId ?: convId; lines += Line(ev.speech.ifEmpty { "(no reply)" }, Kind.REPLY) }
                            is VoiceEvent.Error -> lines += Line(ev.message, Kind.ERROR)
                            is VoiceEvent.Tts -> tts = ev.url
                            is VoiceEvent.Listening -> status = "listening…"
                            is VoiceEvent.VadEnd -> { listening = false; status = "thinking…" }
                            else -> {}
                        }
                    }
                    tts?.let { listening = false; status = "speaking…"; play(client, it) }
                } catch (e: CancellationException) {
                    release()
                    throw e
                } catch (e: Exception) {
                    Log.w("Assist", "listen failed", e)
                    lines += Line(e.message ?: e.toString(), Kind.ERROR)
                    if (e !is AssistError && e !is HaError) { release(); assist = null }
                } finally { ch.close(); busy = false; listening = false; status = null }
            }
        }

        val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) listen() else lines += Line("microphone permission denied", Kind.ERROR)
        }
        fun mic() {
            if (listening) { audio?.close(); status = "thinking…" }
            else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
            else micPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
        var autoStarted by remember { mutableStateOf(false) }
        LaunchedEffect(server?.id) { if (voice && !autoStarted && server != null) { autoStarted = true; mic() } }

        Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(remember { MutableInteractionSource() }, null) { finish() }.imePadding(), Alignment.BottomCenter) {
            Surface(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).clickable(remember { MutableInteractionSource() }, null) {},
                shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)),
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.padding(16.dp).navigationBarsPadding()) {
                    Box {
                        AssistChip(onClick = { menu = true }, enabled = !busy, label = { Text(server?.name ?: "no server") })
                        DropdownMenu(menu, { menu = false }) {
                            servers.forEach {
                                DropdownMenuItem(text = { Text(it.name) }, onClick = {
                                    menu = false
                                    if (it.id != server?.id) {
                                        release()
                                        assist = null; convId = null; chosen = it.id; lines.clear()
                                    }
                                })
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f, fill = false).padding(vertical = 8.dp), listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(lines) { l ->
                            Text(
                                (if (l.kind == Kind.USER) "> " else "") + l.text,
                                color = if (l.kind == Kind.ERROR) Color.Red else Color.Unspecified,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    convId?.let { Text("conversation $it", style = MaterialTheme.typography.labelSmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Ask Assist") })
                        TextButton(::mic, enabled = (!busy || listening) && server != null) { Text(if (listening) "Stop" else "Mic") }
                        Button(::send, enabled = !busy && text.isNotBlank() && server != null) { Text("Send") }
                    }
                }
            }
        }
    }
}

