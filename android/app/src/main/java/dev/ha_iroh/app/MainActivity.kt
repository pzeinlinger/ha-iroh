package dev.ha_iroh.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import uniffi.ha_iroh_core.BridgeInfo

/** Debug screen only; real server model comes with ticket 06. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        val prefs = getSharedPreferences("debug", Context.MODE_PRIVATE)
        setContent {
            val scope = rememberCoroutineScope()
            var uri by remember { mutableStateOf(intent.getStringExtra("ticket") ?: "") } // debug-only: adb ... --es ticket <uri>
            var id by remember { mutableStateOf("...") }
            var out by remember { mutableStateOf("") }
            fun run(f: suspend () -> String) = scope.launch {
                out = "..."
                out = withContext(Dispatchers.IO) { runCatching { f() }.getOrElse { "error: $it" } }
            }
            LaunchedEffect(Unit) { id = withContext(Dispatchers.IO) { runCatching { app.core().endpointId() }.getOrElse { "error: $it" } } }

            Column(Modifier.padding(16.dp).padding(top = 64.dp).verticalScroll(rememberScrollState())) {
                Text("EndpointId: $id")
                OutlinedTextField(uri, { uri = it }, label = { Text("ha-iroh://pair?t=...") })
                Button(onClick = {
                    run {
                        val b = app.core().pair(uri, android.os.Build.MODEL)
                        prefs.edit().putString("name", b.name).putString("info", b.infoJson).apply()
                        "paired with ${b.name}"
                    }
                }) { Text("Pair") }
                Button(onClick = {
                    run {
                        val info = BridgeInfo(prefs.getString("name", null) ?: error("not paired"), prefs.getString("info", null)!!)
                        val tunnel = app.core().connect(info)
                        try {
                            tunnel.okHttpClient().newCall(Request.Builder().url("http://ha.iroh/api/").build())
                                .execute().use { "${it.code}\n${it.body.string()}" }
                        } finally {
                            tunnel.disconnect()
                        }
                    }
                }) { Text("GET /api/") }
                Button(onClick = {
                    run {
                        app.core().wipeKey()
                        app.resetCore()
                        prefs.edit().clear().apply()
                        id = app.core().endpointId()
                        "unpaired"
                    }
                }) { Text("Unpair") }
                Text(out)
            }
        }
    }
}
