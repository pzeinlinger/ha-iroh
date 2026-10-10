package dev.ha_iroh.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ha_iroh_core.BridgeInfo
import uniffi.ha_iroh_core.Tunnel
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private sealed interface Screen {
    data object List : Screen
    data class Add(val uri: String = "") : Screen
    data class Login(val server: Server, val tunnel: Tunnel, val flow: LoginFlow, val step: Step) : Screen
    data class Detail(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        // debug-only: adb ... --es ticket <uri> opens Add with the URI prefilled
        val ticket = intent.getStringExtra("ticket")
        setContent {
            LaunchedEffect(Unit) { app.store.init() }
            MaterialTheme {
                var screen by remember { mutableStateOf<Screen>(if (ticket != null) Screen.Add(ticket) else Screen.List) }
                Column(Modifier.fillMaxSize().padding(16.dp).padding(top = 48.dp)) {
                    when (val s = screen) {
                        Screen.List -> ServerList(app, { screen = Screen.Add() }, { screen = Screen.Detail(it) })
                        is Screen.Add -> AddServer(app, s.uri, { screen = Screen.List }) { screen = it }
                        is Screen.Login -> Login(app, s, { screen = Screen.List }) { screen = it }
                        is Screen.Detail -> Detail(app, s.id, { screen = Screen.List }) { screen = it }
                    }
                }
            }
        }
    }
}

/** Opens a tunnel to the server's bridge and starts the HA login flow. */
private suspend fun beginLogin(app: App, server: Server): Screen.Login = withContext(Dispatchers.IO) {
    val tunnel = app.core().connect(BridgeInfo(server.bridgeInfoName, server.bridgeInfoJson))
    try {
        val flow = LoginFlow(tunnel.okHttpClient())
        Screen.Login(server, tunnel, flow, flow.start())
    } catch (e: Throwable) { tunnel.disconnect(); throw e }
}

@Composable
private fun ServerList(app: App, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val servers by app.store.servers.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column {
            Text("Servers", style = MaterialTheme.typography.headlineSmall)
            servers.forEach {
                ListItem(
                    headlineContent = { Text(it.name) },
                    supportingContent = { Text(if (it.refreshToken == null) "login required" else "logged in") },
                    modifier = Modifier.clickable { onOpen(it.id) },
                )
            }
        }
        ExtendedFloatingActionButton(onClick = onAdd, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd)) { Text("Add server") }
    }
}

@Composable
private fun AddServer(app: App, initial: String, onBack: () -> Unit, onNext: (Screen) -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf(initial) }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scanning = it; if (!it) out = "camera permission denied" }

    fun pair(u: String) = scope.launch {
        busy = true
        out = "pairing..."
        runCatching {
            val b = withContext(Dispatchers.IO) { app.core().pair(u.trim(), android.os.Build.MODEL) }
            beginLogin(app, Server(UUID.randomUUID().toString(), b.name, b.name, b.infoJson))
        }.onSuccess(onNext).onFailure { out = "error: $it"; busy = false }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Add server", style = MaterialTheme.typography.headlineSmall)
        if (scanning) {
            QrScanner { scanning = false; uri = it; pair(it) }
        }
        Button(onClick = { perm.launch(Manifest.permission.CAMERA) }, enabled = !busy) { Text("Scan QR") }
        OutlinedTextField(uri, { uri = it }, label = { Text("ha-iroh://pair?t=...") })
        Button(onClick = { pair(uri) }, enabled = !busy && uri.isNotBlank()) { Text("Pair") }
        Text(out)
    }
}

@Composable
private fun QrScanner(onResult: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(ctx) }
    DisposableEffect(owner) {
        val main = ContextCompat.getMainExecutor(ctx)
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient()
        val done = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(ctx)
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { proxy ->
            val img = proxy.image
            if (img == null || done.get()) { proxy.close(); return@setAnalyzer }
            scanner.process(InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes ->
                    codes.firstNotNullOfOrNull { it.rawValue }?.takeIf { it.startsWith("ha-iroh://") }
                        ?.let { if (done.compareAndSet(false, true)) onResult(it) }
                }
                .addOnCompleteListener { proxy.close() }
        }
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        future.addListener({ future.get().bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }, main)
        onDispose {
            done.set(true)
            future.addListener({ future.get().unbindAll() }, main)
            analysis.clearAnalyzer()
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(modifier = Modifier.fillMaxWidth().height(300.dp), factory = { view })
}

@Composable
private fun Login(app: App, s: Screen.Login, onCancel: () -> Unit, onNext: (Screen) -> Unit) {
    BackHandler(onBack = onCancel)
    DisposableEffect(s.tunnel) { onDispose { Thread { s.tunnel.disconnect() }.start() } }
    val scope = rememberCoroutineScope()
    val values = remember(s.step) { mutableStateMapOf<String, String>() }
    var out by remember(s.step) { mutableStateOf("") }
    var busy by remember(s.step) { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Log in to ${s.server.name}", style = MaterialTheme.typography.headlineSmall)
        s.step.fields.forEach { f ->
            OutlinedTextField(
                values[f.name] ?: "", { values[f.name] = it }, label = { Text(f.name) }, singleLine = true,
                visualTransformation = if (f.name.contains("password")) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
        }
        s.step.errors.forEach { Text("error: $it") }
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                runCatching {
                    when (val r = s.flow.submit(s.step, values.toMap())) {
                        is Step -> s.copy(step = r)
                        is Done -> {
                            val t = s.flow.exchange(r.code)
                            app.store.upsert(s.server.copy(refreshToken = t.refresh))
                            Screen.Detail(s.server.id)
                        }
                    }
                }.onSuccess(onNext).onFailure { out = "error: $it"; busy = false }
            }
        }) { Text("Log in") }
        Text(out)
    }
}

@Composable
private fun Detail(app: App, id: String, onBack: () -> Unit, onLogin: (Screen) -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val server = app.store.servers.collectAsState().value.firstOrNull { it.id == id } ?: return
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun run(f: suspend (HaClient) -> String) = scope.launch {
        busy = true
        out = "..."
        out = try {
            f(app.manager.client(server))
        } catch (e: ReloginRequired) {
            app.store.upsert(server.copy(refreshToken = null))
            "re-login required"
        } catch (e: Throwable) {
            "error: $e"
        } finally {
            withContext(Dispatchers.IO) { app.manager.release(id) }
            busy = false
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(server.name, style = MaterialTheme.typography.headlineSmall)
        Text("id ${server.id}")
        if (server.refreshToken == null) {
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    runCatching { beginLogin(app, server) }.onSuccess(onLogin).onFailure { out = "error: $it"; busy = false }
                }
            }) { Text("Log in") }
        } else {
            Button(enabled = !busy, onClick = { run { it.connect().close(); "auth_ok" } }) { Text("Connect (WS auth)") }
            Button(enabled = !busy, onClick = { run { it.get("/api/") } }) { Text("GET /api/") }
        }
        Button(enabled = !busy, onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { app.manager.release(id) }
                app.store.remove(id)
                onBack()
            }
        }) { Text("Remove server") }
        Text(out)
    }
}
