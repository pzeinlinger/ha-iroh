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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uniffi.ha_iroh_core.BridgeInfo
import uniffi.ha_iroh_core.Tunnel
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private sealed interface Screen {
    data object List : Screen
    data class Add(val uri: String = "") : Screen
    data class Login(val server: Server, val tunnel: Tunnel, val flow: LoginFlow, val step: Step) : Screen
    data class Detail(val id: String) : Screen
    data class Entities(val id: String) : Screen
    data class Dashboard(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        // Android 16+ local network protection: without this runtime grant, iroh's LAN holepunch UDP is silently dropped (relay only).
        if (android.os.Build.VERSION.SDK_INT >= 36 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 0)
        // debug-only: adb ... --es ticket <uri> opens Add with the URI prefilled
        val ticket = intent.getStringExtra("ticket")
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val servers by app.store.servers.collectAsState()
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { app.store.init(); loaded = true }
                // Start on the first logged-in server's dashboard; Back from the server list returns there.
                var active by remember { mutableStateOf<String?>(null) } // last shown dashboard
                val loggedIn = servers.filter { it.refreshToken != null }
                val home: Screen = (loggedIn.firstOrNull { it.id == active } ?: loggedIn.firstOrNull())?.let { Screen.Dashboard(it.id) } ?: Screen.List
                var screen by remember { mutableStateOf<Screen?>(if (ticket != null) Screen.Add(ticket) else null) }
                val cur = screen ?: if (loaded) home else return@MaterialTheme
                if (cur is Screen.Dashboard) {
                    LaunchedEffect(cur.id) { active = cur.id }
                    var info by remember { mutableStateOf<String?>(null) }
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        DashboardBar(servers, cur.id, info, { screen = if (it.refreshToken != null) Screen.Dashboard(it.id) else Screen.Detail(it.id) }, { screen = Screen.List })
                        key(cur.id) { Dashboard(app, cur.id, { screen = Screen.List }, { screen = Screen.Detail(cur.id) }, { info = it }, Modifier.weight(1f)) }
                    }
                    return@MaterialTheme
                }
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                    when (val s = cur) {
                        Screen.List -> ServerList(app, home, { screen = Screen.Add() }, { screen = Screen.Detail(it) }, { screen = it })
                        is Screen.Add -> AddServer(app, s.uri, { screen = Screen.List }) { screen = it }
                        is Screen.Login -> Login(app, s, { screen = Screen.List }) { screen = it }
                        is Screen.Detail -> Detail(app, s.id, { screen = Screen.List }) { screen = it }
                        is Screen.Entities -> EntityPicker(app, s.id) { screen = Screen.Detail(s.id) }
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
private fun ServerList(app: App, home: Screen, onAdd: () -> Unit, onOpen: (String) -> Unit, onNavigate: (Screen) -> Unit) {
    BackHandler(home != Screen.List) { onNavigate(home) }
    val servers by app.store.servers.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Servers", style = MaterialTheme.typography.headlineSmall)
            servers.forEach {
                ListItem(
                    headlineContent = { Text(it.name) },
                    supportingContent = { Text(if (it.refreshToken == null) "login required" else "logged in") },
                    modifier = Modifier.clickable { onOpen(it.id) },
                )
            }
            AppSettings(app)
            Spacer(Modifier.height(80.dp)) // clear of the FAB
        }
        ExtendedFloatingActionButton(onClick = onAdd, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd)) { Text("Add server") }
    }
}

@Composable
private fun AppSettings(app: App) {
    val clipboard = LocalClipboardManager.current
    var endpointId by remember { mutableStateOf("...") }
    LaunchedEffect(Unit) { endpointId = withContext(Dispatchers.IO) { runCatching { app.core().endpointId() }.getOrElse { "error: $it" } } }
    var custom by remember { mutableStateOf(app.prefs.getString("relay_urls", "").orEmpty().isNotEmpty()) }
    var urls by remember { mutableStateOf(app.prefs.getString("relay_urls", "").orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    fun save(text: String) {
        val all = parseRelayUrls(text)
        val ok = all.filter { it.toHttpUrlOrNull() != null }
        invalid = ok.size != all.size
        app.prefs.edit().putString("relay_urls", ok.joinToString("\n")).apply()
    }
    Text("App settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
    Text("EndpointId", style = MaterialTheme.typography.labelMedium)
    Text(endpointId, style = MaterialTheme.typography.bodySmall)
    Button({ clipboard.setText(AnnotatedString(endpointId)) }) { Text("Copy EndpointId") }
    Text("Relay", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        RadioButton(!custom, { custom = false; app.prefs.edit().remove("relay_urls").apply() }); Text("n0 (default)")
        RadioButton(custom, { custom = true; save(urls) }); Text("custom")
    }
    if (custom) OutlinedTextField(urls, {
        urls = it
        save(it)
    }, label = { Text("relay URLs (comma or newline separated)") })
    if (invalid) Text("invalid URL (not saved)", color = MaterialTheme.colorScheme.error)
    Text("takes effect after app restart", style = MaterialTheme.typography.labelSmall)
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
private fun Detail(app: App, id: String, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val server = app.store.servers.collectAsState().value.firstOrNull { it.id == id } ?: return
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pipelines by remember { mutableStateOf<List<Pipeline>?>(null) }
    fun run(f: suspend (HaClient) -> String) = scope.launch {
        busy = true
        out = "..."
        out = try {
            f(app.manager.client(server))
        } catch (e: ReloginRequired) {
            app.store.update(id) { it.copy(refreshToken = null) }
            "re-login required"
        } catch (e: Throwable) {
            "error: $e"
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { app.manager.release(id) }
            busy = false
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(server.name, style = MaterialTheme.typography.headlineSmall)
        Text("id ${server.id}")
        var name by remember(server.name) { mutableStateOf(server.name) }
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        Button(enabled = !busy && name.isNotBlank() && name.trim() != server.name, onClick = { scope.launch { app.store.update(id) { it.copy(name = name.trim()) } } }) { Text("Rename") }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(server.isDefaultAssist, { if (it) scope.launch { app.store.setDefaultAssist(id) } else scope.launch { app.store.update(id) { s -> s.copy(isDefaultAssist = false) } } })
            Text(" Default for Assist")
        }
        if (server.refreshToken == null) {
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    runCatching { beginLogin(app, server) }.onSuccess(onNavigate).onFailure { out = "error: $it"; busy = false }
                }
            }) { Text("Log in") }
        } else {
            Button(enabled = !busy, onClick = { run { it.connect().close(); "auth_ok" } }) { Text("Connect (WS auth)") }
            Button(enabled = !busy, onClick = { run { it.get("/api/") } }) { Text("GET /api/") }
            Button(enabled = !busy, onClick = { onNavigate(Screen.Entities(id)) }) { Text("Entities") }
            Button(enabled = !busy, onClick = { scope.launch { app.store.update(id) { it.copy(refreshToken = null) } } }) { Text("Log out") }
            Text("Pipeline: ${pipelines?.firstOrNull { it.id == server.pipelineId }?.name ?: server.pipelineId ?: "HA preferred"}")
            Button(enabled = !busy, onClick = {
                run { c ->
                    pipelines = parsePipelines(c.connect().sendCommand(buildJsonObject { put("type", "assist_pipeline/pipeline/list") }))
                    ""
                }
            }) { Text("Pipeline") }
            pipelines?.let { list ->
                fun pick(pid: String?) = scope.launch { app.store.update(id) { it.copy(pipelineId = pid) }; pipelines = null }
                TextButton({ pick(null) }) { Text("HA preferred" + if (server.pipelineId == null) " (selected)" else "") }
                list.forEach { TextButton({ pick(it.id) }) { Text(it.name + (if (it.preferred) " (HA preferred)" else "") + if (it.id == server.pipelineId) " (selected)" else "") } }
            }
        }
        Button(enabled = !busy, onClick = {
            scope.launch {
                withContext(NonCancellable + Dispatchers.IO) {
                    app.manager.release(id)
                    app.store.remove(id)
                    File(app.filesDir, "entities-$id.json").delete()
                    app.areaFile(id).delete()
                    // Decision: the app key (EndpointId) is wiped only with the last server, so other servers stay paired.
                    if (app.store.servers.value.isEmpty()) app.wipeCore()
                }
                onBack()
            }
        }) { Text("Unpair") }
        Text(out)
    }
}

/** Pick which entities (any supported domain) show up in Device Controls; also seeds the state cache. */
@Composable
private fun EntityPicker(app: App, id: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val server = app.store.servers.collectAsState().value.firstOrNull { it.id == id } ?: return
    var entities by remember { mutableStateOf<Map<String, List<EntityState>>?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(id) {
        var client: HaClient? = null
        try {
            client = app.manager.client(server)
            val session = client.connect()
            val all = EntityDiff.parseStates(session.sendCommand(buildJsonObject { put("type", "get_states") }))
                .filterValues { it.toSpec() != null }
            withContext(Dispatchers.IO) { app.entityCache(id).let { it.save(it.load() + all) } }
            entities = all.values.sortedBy { it.entityId }.groupBy { domainOf(it.entityId) }
            withContext(Dispatchers.IO) { session.refreshAreas(app.areaFile(id)) }
        } catch (e: Throwable) {
            err = "error: $e"
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { client?.close() }
        }
    }
    val scope = rememberCoroutineScope()
    LazyColumn {
        item { Text("Entities on ${server.name}", style = MaterialTheme.typography.headlineSmall) }
        if (entities == null && err.isEmpty()) item { Text("loading...") }
        item { Text(err) }
        entities?.forEach { (domain, list) ->
            item { Text(domain, style = MaterialTheme.typography.titleMedium) }
            items(list, key = { it.entityId }) { e ->
                val on = e.entityId in server.selectedEntities
                ListItem(
                    headlineContent = { Text((e.attributes["friendly_name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: e.entityId) },
                    supportingContent = { Text(e.entityId) },
                    trailingContent = {
                        Checkbox(on, { c ->
                            scope.launch {
                                app.store.update(id) {
                                    it.copy(selectedEntities = if (c) (it.selectedEntities + e.entityId).distinct() else it.selectedEntities - e.entityId)
                                }
                            }
                        })
                    },
                )
            }
        }
    }
}
