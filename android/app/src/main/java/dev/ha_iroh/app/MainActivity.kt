package dev.ha_iroh.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val COFFEE_URL = "https://buymeacoffee.com/pzeinlinger"

private sealed interface Screen {
    data object Settings : Screen
    data class Add(val uri: String = "") : Screen
    data class Login(val server: Server) : Screen
    data class Detail(val id: String) : Screen
    data class Entities(val id: String) : Screen
    data class Dashboard(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    private var launched by mutableStateOf<Intent?>(null) // latest delivered intent, consumed by the composition

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launched = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        // Android 16+ local network protection: without this runtime grant, iroh's LAN holepunch UDP is silently dropped (relay only).
        if (android.os.Build.VERSION.SDK_INT >= 36 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 0)
        // debug-only: adb ... --es ticket <uri> opens Add with the URI prefilled
        // debug builds only: the activity is exported, so any app could otherwise prefill a hostile pairing URI
        val ticket = if (BuildConfig.DEBUG && savedInstanceState == null) intent.getStringExtra("ticket") else null // not again after a config recreate
        setContent {
            AppTheme {
                val servers by app.store.servers.collectAsState()
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { app.store.init(); loaded = true }
                // Start on the first logged-in server's dashboard; Back from Settings returns there.
                var active by remember { mutableStateOf(intent.getStringExtra(EXTRA_SERVER_ID)) } // last shown dashboard
                val loggedIn = servers.filter { it.refreshToken != null }
                val home: Screen = (loggedIn.firstOrNull { it.id == active } ?: loggedIn.firstOrNull())?.let { Screen.Dashboard(it.id) } ?: Screen.Settings
                var screen by remember { mutableStateOf<Screen?>(if (ticket != null) Screen.Add(ticket) else null) }
                // Device Controls tap while the app is already open (the system may reuse this instance): jump to that server
                LaunchedEffect(launched) {
                    launched?.getStringExtra(EXTRA_SERVER_ID)?.let { id -> servers.firstOrNull { it.id == id } }?.let { screen = if (it.refreshToken != null) Screen.Dashboard(it.id) else Screen.Detail(it.id) }
                    launched = null
                }
                val cur = screen ?: if (loaded) home else return@AppTheme
                CoffeePrompt(app, loggedIn.isNotEmpty())
                when (val s = cur) {
                    is Screen.Dashboard -> {
                        LaunchedEffect(s.id) { active = s.id }
                        var info by remember { mutableStateOf<String?>(null) }
                        Scaffold(topBar = {
                            DashboardBar(servers, s.id, info, { screen = if (it.refreshToken != null) Screen.Dashboard(it.id) else Screen.Detail(it.id) }, { screen = Screen.Settings })
                        }) { pad ->
                            key(s.id) { Dashboard(app, s.id, { screen = Screen.Settings }, { screen = Screen.Detail(s.id) }, { info = it }, Modifier.padding(pad)) }
                        }
                    }
                    Screen.Settings -> SettingsScreen(app, home, { screen = Screen.Add() }, { screen = Screen.Detail(it) }, { screen = it })
                    is Screen.Add -> AddServer(app, s.uri, { screen = Screen.Settings }) { screen = it }
                    is Screen.Login -> Login(app, s.server, { screen = Screen.Detail(s.server.id) }) { screen = Screen.Detail(s.server.id) }
                    is Screen.Detail -> Detail(app, s.id, { screen = Screen.Settings }) { screen = it }
                    is Screen.Entities -> EntityPicker(app, s.id) { screen = Screen.Detail(s.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(app: App, home: Screen, onAdd: () -> Unit, onOpen: (String) -> Unit, onNavigate: (Screen) -> Unit) {
    BackHandler(home != Screen.Settings) { onNavigate(home) }
    val servers by app.store.servers.collectAsState()
    val clipboard = LocalClipboardManager.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var endpointId by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) { endpointId = withContext(Dispatchers.IO) { runCatching { app.core().endpointId() }.getOrElse { "Error: ${it.userMessage()}" } } }
    var relay by remember { mutableStateOf(app.prefs.getString("relay_urls", "").orEmpty()) }
    var relayDialog by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current

    Scaffold(topBar = { TopAppBar({ Text("Settings") }, navigationIcon = { if (home != Screen.Settings) BackArrow() }) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Section("Servers")
            if (servers.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Home, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("No servers yet", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
                    Button(onAdd, Modifier.padding(top = 16.dp)) { Text("Add server") }
                }
            } else {
                // Drag the handle to reorder; local copy during the drag, persisted on release.
                var order by remember(servers) { mutableStateOf(servers) }
                var dragging by remember { mutableStateOf<String?>(null) }
                var dragY by remember { mutableFloatStateOf(0f) }
                val heights = remember { mutableStateMapOf<String, Int>() }
                order.forEach { s -> key(s.id) {
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text(if (s.refreshToken == null) "Login required" else "Logged in") },
                        leadingContent = {
                            if (servers.size > 1) Icon(Icons.Filled.Menu, "Reorder", Modifier.pointerInput(s.id) {
                                detectDragGestures(
                                    onDragStart = { dragging = s.id; dragY = 0f },
                                    onDragEnd = { dragging = null; scope.launch { app.store.reorder(order.map { it.id }) } },
                                    onDragCancel = { dragging = null; order = servers },
                                ) { change, d ->
                                    change.consume()
                                    dragY += d.y
                                    val i = order.indexOfFirst { it.id == s.id }
                                    val next = order.getOrNull(i + 1)?.let { heights[it.id] } ?: Int.MAX_VALUE
                                    val prev = order.getOrNull(i - 1)?.let { heights[it.id] } ?: Int.MAX_VALUE
                                    if (dragY > next / 2f) { order = order.swap(i, i + 1); dragY -= next }
                                    else if (dragY < -prev / 2f) { order = order.swap(i, i - 1); dragY += prev }
                                }
                            })
                        },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier
                            .zIndex(if (s.id == dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (s.id == dragging) dragY else 0f }
                            .onSizeChanged { heights[s.id] = it.height }
                            .clickable { onOpen(s.id) },
                    )
                } }
                ListItem({ Text("Add server") }, Modifier.clickable(onClick = onAdd), leadingContent = { Icon(Icons.Filled.Add, null) })
            }
            Section("App")
            ListItem(
                { Text("Endpoint ID") },
                Modifier.clickable { clipboard.setText(AnnotatedString(endpointId)); scope.launch { snack.showSnackbar("Copied") } },
                supportingContent = { Text(endpointId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            ListItem(
                { Text("Relay") },
                Modifier.clickable { relayDialog = true },
                supportingContent = { Text(if (relay.isEmpty()) "Default (n0)" else relay.replace("\n", ", ")) },
            )
            Section("Support")
            ListItem(
                { Text("Buy me a coffee") },
                Modifier.clickable { uri.openUri(COFFEE_URL) },
                supportingContent = { Text("ha-iroh is a one-person project, free and without ads. A coffee keeps it going.") },
                leadingContent = { Icon(Icons.Filled.Favorite, null) },
            )
            ListItem(
                { Text("Source code & issues") },
                Modifier.clickable { uri.openUri("https://github.com/pzeinlinger/ha-iroh") },
                supportingContent = { Text("github.com/pzeinlinger/ha-iroh") },
            )
        }
    }
    if (relayDialog) RelayDialog(relay, { relay = it; app.prefs.edit().putString("relay_urls", it).apply(); relayDialog = false }) { relayDialog = false }
}

@Composable
private fun RelayDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(initial.isNotEmpty()) }
    var urls by remember { mutableStateOf(initial) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Relay") },
        text = {
            Column {
                Row(Modifier.clickable { custom = false }, verticalAlignment = Alignment.CenterVertically) { RadioButton(!custom, { custom = false }); Text("Default (n0)") }
                Row(Modifier.clickable { custom = true }, verticalAlignment = Alignment.CenterVertically) { RadioButton(custom, { custom = true }); Text("Custom") }
                if (custom) OutlinedTextField(urls, { urls = it; invalid = false }, label = { Text("Relay URLs (comma or newline separated)") })
                if (invalid) Text("Invalid URL", color = MaterialTheme.colorScheme.error)
                Text("Takes effect after app restart", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton({
                val all = if (custom) parseRelayUrls(urls) else emptyList()
                if (all.any { it.toHttpUrlOrNull() == null }) invalid = true else onSave(all.joinToString("\n"))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private enum class AddMode { Scan, Paste, Connecting, Failed }

/** One-time "buy me a coffee" dialog, a few seconds after the first server is paired and logged in (the login itself went through the tunnel). Never shown again, whatever the user taps. */
@Composable
private fun CoffeePrompt(app: App, connected: Boolean) {
    var show by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LaunchedEffect(connected) {
        if (!connected || app.prefs.getBoolean("coffee_shown", false)) return@LaunchedEffect
        kotlinx.coroutines.delay(5_000)
        show = true
    }
    if (!show) return
    fun done() { app.prefs.edit().putBoolean("coffee_shown", true).apply(); show = false }
    AlertDialog(
        onDismissRequest = ::done,
        icon = { Text("\u2615", fontSize = 40.sp) },
        title = { Text("You're all set!") },
        text = { Text("ha-iroh is a one-person weekend project, free and without ads. If it saves you a minute in front of the garage door, a coffee is very welcome.") },
        confirmButton = { TextButton({ done(); uri.openUri(COFFEE_URL) }) { Text("Buy me a coffee") } },
        dismissButton = { TextButton(::done) { Text("Maybe later") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddServer(app: App, initial: String, onBack: () -> Unit, onNext: (Screen) -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var uri by remember { mutableStateOf(initial) }
    var mode by remember { mutableStateOf(if (initial.isNotEmpty()) AddMode.Paste else AddMode.Scan) }
    var camera by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera = it; if (!it) { denied = true; mode = AddMode.Paste } }
    LaunchedEffect(mode) { if (mode == AddMode.Scan && !camera) perm.launch(Manifest.permission.CAMERA) }

    fun connect(u: String) {
        val from = mode
        mode = AddMode.Connecting
        scope.launch {
            try {
                val b = withContext(Dispatchers.IO) { app.core().pair(u.trim(), android.os.Build.MODEL) }
                val server = Server(UUID.randomUUID().toString(), b.name, b.name, b.infoJson)
                app.store.upsert(server) // a cancelled first login leaves a "Login required" row instead of losing the pairing
                onNext(Screen.Login(server))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mode = AddMode.Failed
                snack.showSnackbar("Couldn't connect: ${e.userMessage()}", "Retry", withDismissAction = true, duration = SnackbarDuration.Indefinite)
                mode = from // retry (or dismiss) reopens the scanner / paste form
            }
        }
    }

    Scaffold(topBar = { TopAppBar({ Text("Add server") }, navigationIcon = { BackArrow() }) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding()) {
            when (mode) {
                AddMode.Scan -> {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                    if (camera) QrScanner(Modifier.fillMaxSize()) { uri = it; connect(it) } // else: permission dialog is up
                    Viewfinder(Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Scan the pairing QR code", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        TextButton({ mode = AddMode.Paste }) { Text("Enter code instead", color = Color.White) }
                    }
                }
                AddMode.Paste -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(if (denied) "Camera permission denied. Paste the pairing code instead." else "Paste the pairing code from the bridge.", Modifier.padding(bottom = 16.dp))
                    OutlinedTextField(uri, { uri = it }, Modifier.fillMaxWidth().heightIn(max = 120.dp), maxLines = 3, label = { Text("ha-iroh://pair?t=…") })
                    Button({ connect(uri) }, Modifier.padding(top = 16.dp), enabled = uri.isNotBlank()) { Text("Connect") }
                    TextButton({ denied = false; mode = AddMode.Scan }) { Text("Scan QR instead") }
                }
                AddMode.Connecting -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Connecting…", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                }
                AddMode.Failed -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Text("Couldn't connect", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Dimmed camera with a rounded cut-out and corner brackets. */
@Composable
private fun Viewfinder(modifier: Modifier) = Canvas(modifier) {
    val side = size.minDimension * 0.65f
    val tl = Offset((size.width - side) / 2, (size.height - side) / 2)
    val box = Rect(tl, Size(side, side))
    drawPath(Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRoundRect(RoundRect(box, CornerRadius(24.dp.toPx())))
    }, Color.Black.copy(alpha = 0.5f))
    val len = side * 0.18f
    val w = 5.dp.toPx()
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(Color.White, Offset(x, y), Offset(x + dx * len, y), w, StrokeCap.Round)
        drawLine(Color.White, Offset(x, y), Offset(x, y + dy * len), w, StrokeCap.Round)
    }
    corner(box.left, box.top, 1f, 1f); corner(box.right, box.top, -1f, 1f)
    corner(box.left, box.bottom, 1f, -1f); corner(box.right, box.bottom, -1f, -1f)
}

@Composable
private fun QrScanner(modifier: Modifier, onResult: (String) -> Unit) {
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
    AndroidView(modifier = modifier, factory = { view })
}

private enum class Dlg { None, Rename, Pipeline, Unpair }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Detail(app: App, id: String, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val server = app.store.servers.collectAsState().value.firstOrNull { it.id == id } ?: return
    val snack = remember { SnackbarHostState() }
    val loggedIn = server.refreshToken != null
    var busy by remember { mutableStateOf(false) }
    var pipelines by remember { mutableStateOf<List<Pipeline>?>(null) }
    var dialog by remember { mutableStateOf(Dlg.None) }
    fun toast(m: String) { scope.launch { snack.showSnackbar(m) } }

    fun openPipelines() = scope.launch {
        busy = true
        try {
            pipelines = parsePipelines(app.manager.client(server).connect().sendCommand(buildJsonObject { put("type", "assist_pipeline/pipeline/list") }))
            dialog = Dlg.Pipeline
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReloginRequired) {
            app.store.update(id) { it.copy(refreshToken = null) }
            toast("Login expired")
        } catch (e: Exception) {
            toast("Error: ${e.userMessage()}")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { app.manager.release(id) }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar({ Text(server.name) }, navigationIcon = { BackArrow() },
                actions = { IconButton({ dialog = Dlg.Rename }) { Icon(Icons.Filled.Edit, "Rename") } })
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Section("Account")
            ListItem(
                { Text("Status") },
                supportingContent = { Text(if (loggedIn) "Logged in" else "Login required") },
                trailingContent = {
                    if (loggedIn) TextButton({ scope.launch { app.store.update(id) { it.copy(refreshToken = null) } } }) { Text("Log out") }
                    else Button({ onNavigate(Screen.Login(server)) }) { Text("Log in") }
                },
            )
            Section("Assist")
            ListItem(
                { Text("Default for Assist") },
                trailingContent = { Switch(server.isDefaultAssist, { if (it) scope.launch { app.store.setDefaultAssist(id) } else scope.launch { app.store.update(id) { s -> s.copy(isDefaultAssist = false) } } }) },
            )
            ListItem(
                { Text("Pipeline") },
                Modifier.clickable(enabled = loggedIn && !busy) { openPipelines() },
                supportingContent = { Text(pipelines?.firstOrNull { it.id == server.pipelineId }?.name ?: if (server.pipelineId == null) "HA preferred" else "Custom") },
                trailingContent = { if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
            )
            Section("Device Controls")
            ListItem(
                { Text("Choose entities") },
                Modifier.clickable(enabled = loggedIn) { onNavigate(Screen.Entities(id)) },
                supportingContent = { Text("${server.selectedEntities.count(::isControllable)} selected") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
            Section("Danger zone")
            ListItem(
                { Text("Unpair", color = MaterialTheme.colorScheme.error) },
                Modifier.clickable { dialog = Dlg.Unpair },
                supportingContent = { Text("Remove this server from the app") },
            )
        }
    }

    when (dialog) {
        Dlg.None -> {}
        Dlg.Rename -> {
            var name by remember { mutableStateOf(server.name) }
            AlertDialog(
                onDismissRequest = { dialog = Dlg.None },
                title = { Text("Rename server") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
                confirmButton = {
                    TextButton({ scope.launch { app.store.update(id) { it.copy(name = name.trim()) }; dialog = Dlg.None } }, enabled = name.isNotBlank()) { Text("Save") }
                },
                dismissButton = { TextButton({ dialog = Dlg.None }) { Text("Cancel") } },
            )
        }
        Dlg.Pipeline -> AlertDialog(
            onDismissRequest = { dialog = Dlg.None },
            title = { Text("Assist pipeline") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    fun pick(pid: String?) { scope.launch { app.store.update(id) { it.copy(pipelineId = pid) }; dialog = Dlg.None } }
                    Row(Modifier.clickable { pick(null) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(server.pipelineId == null, { pick(null) }); Text("HA preferred") }
                    pipelines.orEmpty().forEach {
                        Row(Modifier.clickable { pick(it.id) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(it.id == server.pipelineId, { pick(it.id) }); Text(it.name + if (it.preferred) " (HA preferred)" else "")
                        }
                    }
                }
            },
            confirmButton = { TextButton({ dialog = Dlg.None }) { Text("Close") } },
        )
        Dlg.Unpair -> AlertDialog(
            onDismissRequest = { dialog = Dlg.None },
            title = { Text("Unpair ${server.name}?") },
            text = { Text("The server is removed from this app. Pair again to restore access.") },
            confirmButton = {
                TextButton({
                    dialog = Dlg.None
                    scope.launch {
                        withContext(NonCancellable + Dispatchers.IO) {
                            // best effort: HA refresh tokens never expire, so revoke server-side before forgetting it
                            runCatching { app.manager.client(server).revoke() }
                            app.manager.release(id)
                            app.store.remove(id)
                            File(app.filesDir, "entities-$id.json").delete()
                            app.areaFile(id).delete()
                            // Decision: the app key (EndpointId) is wiped only with the last server, so other servers stay paired.
                            if (app.store.servers.value.isEmpty()) app.wipeCore()
                        }
                        onBack()
                    }
                }) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ dialog = Dlg.None }) { Text("Cancel") } },
        )
    }
}

private val DOMAIN_ICON = mapOf(
    "light" to R.drawable.ic_light, "switch" to R.drawable.ic_switch, "fan" to R.drawable.ic_fan, "climate" to R.drawable.ic_climate)
// ponytail: no extended icon set; the rest reuse material-icons-core glyphs
private val CORE_ICON = mapOf<String, ImageVector>("input_boolean" to Icons.Filled.Check, "cover" to Icons.Filled.KeyboardArrowUp, "lock" to Icons.Filled.Lock,
    "scene" to Icons.Filled.PlayArrow, "script" to Icons.Filled.PlayArrow, "button" to Icons.Filled.PlayArrow)

@Composable
private fun DomainIcon(domain: String) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    DOMAIN_ICON[domain]?.let { Icon(painterResource(it), null, tint = tint) } ?: Icon(CORE_ICON[domain] ?: Icons.Filled.Info, null, tint = tint)
}

/** Pick which entities (any supported domain) show up in Device Controls; also seeds the state cache. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntityPicker(app: App, id: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val server = app.store.servers.collectAsState().value.firstOrNull { it.id == id } ?: return
    var entities by remember { mutableStateOf<List<EntityState>?>(null) }
    var reg by remember { mutableStateOf(Registry()) }
    var err by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(id) {
        var client: HaClient? = null
        try {
            client = app.manager.client(server)
            val session = client.connect()
            val all = EntityDiff.parseStates(session.sendCommand(buildJsonObject { put("type", "get_states") }))
                .filterValues { it.toSpec() != null }
            withContext(Dispatchers.IO) { app.entityCache(id).let { it.save(it.load() + all) } }
            reg = withContext(Dispatchers.IO) { loadAreas(app.areaFile(id)) } // last known, until the refresh below lands
            entities = all.values.toList()
            reg = withContext(Dispatchers.IO) { session.refreshAreas(app.areaFile(id)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            err = "Error: ${e.userMessage()}"
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { client?.close() }
        }
    }
    val scope = rememberCoroutineScope()
    val selected = server.selectedEntities.toSet()
    val groups = remember(entities, reg, selected, query) { pickerGroups(entities.orEmpty(), reg, selected, query) }
    val visible = groups.flatMap { g -> g.second.map { it.entityId } }
    fun setAll(ids: List<String>, on: Boolean) {
        scope.launch { app.store.update(id) { it.copy(selectedEntities = if (on) (it.selectedEntities + ids).distinct() else it.selectedEntities - ids.toSet()) } }
    }

    Scaffold(topBar = { TopAppBar({ Text("Device Controls") }, navigationIcon = { BackArrow() }) }) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
                placeholder = { Text("Search") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
            )
            if (entities == null && err.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            if (err.isNotEmpty()) Text(err, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            if (entities != null && groups.isEmpty()) Text("No matching entities", Modifier.padding(16.dp))
            LazyColumn {
                if (visible.isNotEmpty()) item("all") {
                    ListItem({ Text("Select all") }, trailingContent = { Switch(visible.all { it in selected }, { setAll(visible, it) }) })
                }
                groups.forEach { (area, list) ->
                    val ids = list.map { it.entityId }
                    val all = ids.all { it in selected }
                    item("h:$area") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Section(area, Modifier)
                            TextButton({ setAll(ids, !all) }) { Text(if (all) "Clear" else "Select all") }
                        }
                    }
                    items(list, key = { it.entityId }) { e ->
                        val on = e.entityId in selected
                        ListItem(
                            headlineContent = { Text(e.title()) },
                            supportingContent = { Text(e.entityId) },
                            leadingContent = { DomainIcon(domainOf(e.entityId)) },
                            trailingContent = { Switch(on, { setAll(listOf(e.entityId), it) }) },
                            modifier = Modifier.clickable { setAll(listOf(e.entityId), !on) },
                        )
                    }
                }
            }
        }
    }
}

private fun <T> List<T>.swap(i: Int, j: Int) = toMutableList().also { it[i] = it[j].also { _ -> it[j] = this[i] } }
