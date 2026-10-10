package dev.ha_iroh.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val EXTRA_SERVER_ID = "serverId"
const val EXTRA_VOICE = "voice"

private enum class Kind { USER, REPLY, ERROR }
private data class Line(val text: String, val kind: Kind)

/** Translucent bottom sheet; one tunnel + session per chosen server, released in [onStop] (no warm keeping). */
class AssistActivity : ComponentActivity() {
    private val app get() = application as App
    private var job: Job? = null // connect + ask for the current server
    private var activeId: String? = null // server whose tunnel may be open

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extraId = intent.getStringExtra(EXTRA_SERVER_ID)
        val voice = intent.getBooleanExtra(EXTRA_VOICE, false)
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

    @Composable
    private fun Sheet(extraId: String?, voice: Boolean) {
        val servers by app.store.servers.collectAsState()
        LaunchedEffect(Unit) { app.store.init() }
        var chosen by remember { mutableStateOf(extraId) }
        val server = servers.firstOrNull { it.id == chosen } ?: servers.firstOrNull { it.isDefaultAssist } ?: servers.firstOrNull()
        val lines = remember { mutableStateListOf<Line>().apply { if (voice) add(Line("voice: later", Kind.REPLY)) } }
        var convId by remember { mutableStateOf<String?>(null) }
        var assist by remember { mutableStateOf<AssistSession?>(null) }
        var busy by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        var menu by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

        fun send() {
            val s = server ?: return
            val msg = text.trim().ifEmpty { return }
            text = ""; busy = true; lines += Line(msg, Kind.USER)
            job = scope.launch {
                try {
                    val a = assist ?: run {
                        activeId = s.id // set before connecting so a stop mid-connect still releases s.id
                        AssistSession(app.manager.client(s).connect()).also { assist = it }
                    }
                    val r = a.ask(msg, convId, s.pipelineId).getOrThrow()
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
                    convId?.let { Text("conversation $it", style = MaterialTheme.typography.labelSmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Ask Assist") })
                        // voice is ticket 10
                        TextButton({}, enabled = false) { Text("mic: later") }
                        Button(::send, enabled = !busy && text.isNotBlank() && server != null) { Text("Send") }
                    }
                }
            }
        }
    }
}

