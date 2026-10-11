package dev.ha_iroh.app

import android.annotation.SuppressLint
import android.util.Log
import android.widget.Toast
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import uniffi.ha_iroh_core.BridgeInfo
import uniffi.ha_iroh_core.LoopbackProxy
import uniffi.ha_iroh_core.Tunnel
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "Dashboard"
private const val NATIVE = "externalAppNative" // WebMessageListener object; window.externalApp is a JS shim over it
private val CALLBACK = Regex("[A-Za-z_]\\w*")

// Same contract as the official app's `externalApp`, forwarded to the origin-scoped message listener.
private val SHIM = """
window.externalApp = {
  getExternalAuth: p => $NATIVE.postMessage(JSON.stringify({method: "getExternalAuth", payload: p})),
  revokeExternalAuth: p => $NATIVE.postMessage(JSON.stringify({method: "revokeExternalAuth", payload: p})),
  externalBus: p => $NATIVE.postMessage(JSON.stringify({method: "externalBus", payload: p})),
};
// HA scrolls an inner element in shadow DOM, so WebView.scrollY is always 0. SwipeRefreshLayout decides on touch-down, so the
// value must be current *before* a gesture: hook `scroll` on whichever scroller gets touched and report on every scroll (flings too).
const report = v => $NATIVE.postMessage(JSON.stringify({method: "canScrollUp", payload: String(v)}));
const up = el => { for (let n = el; n; n = n.parentNode instanceof ShadowRoot ? n.parentNode.host : n.parentNode) if (n instanceof Element && n.scrollTop > 0) return true; return false; };
const hooked = new WeakSet();
document.addEventListener("touchstart", e => {
  const path = e.composedPath();
  report(up(path[0]));
  for (const el of path) if (el instanceof Element && !hooked.has(el) && el.scrollHeight > el.clientHeight) {
    hooked.add(el); el.addEventListener("scroll", () => report(up(el)), {passive: true});
  }
}, {capture: true, passive: true});
"""

internal fun String?.inOrigin(origin: String?) = this != null && origin != null && (this == origin || startsWith("$origin/"))

/** One resume..pause worth of tunnel + proxy. */
private class Live(val tunnel: Tunnel, val proxy: LoopbackProxy, val client: HaClient) {
    val origin = "http://${proxy.token()}.localhost:${proxy.port()}"
    /** Blocking (uniffi): call off the main thread. */
    fun close() { proxy.stop(); client.close() } // client.close() disconnects the tunnel
}

/** Main screen: the HA frontend of [serverId] through the loopback proxy (SPEC §8.6). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Dashboard(app: App, serverId: String, onSettings: () -> Unit, onRelogin: () -> Unit, onInfo: (String?) -> Unit = {}, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var canBack by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val state = remember { object { val live = AtomicReference<Live?>(null); var job: Job? = null; var script: ScriptHandler? = null; @Volatile var canScrollUp = false /* ponytail: only the modern shim reports; the fallback interface never does, so refresh stays eager there */ } }

    val web = remember {
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect over adb
        WebView(ctx).apply {
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    Log.d(TAG, "console ${m.messageLevel()} ${m.sourceId()}:${m.lineNumber()} ${m.message()}"); return true
                }
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
        }
    }
    // Pull down at the top of the page = full refresh: new tunnel, new proxy, reload.
    // WebView must be MATCH_PARENT: with wrap-content params Chromium resolves 100vh / min-height media queries to 0
    // and the HA frontend's full-height pages (Settings, HACS) render blank.
    val swipe = remember {
        SwipeRefreshLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnChildScrollUpCallback { _, _ -> state.canScrollUp || web.scrollY > 0 }
        }
    }
    val modern = remember {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }

    fun reply(js: String) = web.evaluateJavascript(js, null)

    /** One external-app call; [method] is the `externalApp` method name, [payload] its JSON string argument. */
    suspend fun handle(method: String, payload: String) {
        var cb: String? = null
        try {
            val o = json.parseToJsonElement(payload).jsonObject
            cb = o["callback"]?.jsonPrimitive?.content?.takeIf { CALLBACK.matches(it) }
            when (method) {
                "getExternalAuth" -> {
                    val client = state.live.get()?.client ?: return
                    try {
                        val (token, expiresIn) = client.accessToken(o["force"]?.jsonPrimitive?.booleanOrNull == true)
                        reply("window.$cb(true, ${buildJsonObject { put("access_token", token); put("expires_in", expiresIn) }})")
                    } catch (e: ReloginRequired) {
                        reply("window.$cb(false)")
                        app.store.update(serverId) { it.copy(refreshToken = null) }
                        onRelogin()
                    }
                }
                "revokeExternalAuth" -> {
                    reply("window.$cb(true)")
                    app.store.update(serverId) { it.copy(refreshToken = null) }
                    onRelogin()
                }
                "externalBus" -> when (o["type"]?.jsonPrimitive?.content) {
                    "config/get" -> reply("window.externalBus(${buildJsonObject {
                        put("id", o["id"] ?: JsonNull)
                        put("type", "result")
                        put("success", true)
                        putJsonObject("result") { put("hasSettingsScreen", true); put("canWriteTag", false) }
                    }})")
                    "config_screen/show" -> onSettings()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$method failed", e)
            cb?.let { reply("window.$it(false)") }
        }
    }

    remember(web) { // install once
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.toString().inOrigin(state.live.get()?.origin)) return false
                if (u.scheme == "http" || u.scheme == "https") runCatching { CustomTabsIntent.Builder().build().launchUrl(ctx, u) }
                return true // never navigate this WebView away from the proxy origin
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { canBack = view.canGoBack() }
            override fun onPageFinished(view: WebView, url: String?) { swipe.isRefreshing = false; state.canScrollUp = false }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer gone crash=${detail.didCrash()}"); error = "WebView renderer died; reopen the dashboard"; return true
            }
        }
        if (!modern) {
            // Fallback: exposed to every frame, so only act while the top-level page is on our origin.
            fun guarded(method: String, payload: String) { scope.launch { if (web.url.inOrigin(state.live.get()?.origin)) handle(method, payload) } }
            web.addJavascriptInterface(object {
                @JavascriptInterface fun getExternalAuth(p: String) = guarded("getExternalAuth", p)
                @JavascriptInterface fun revokeExternalAuth(p: String) = guarded("revokeExternalAuth", p)
                @JavascriptInterface fun externalBus(p: String) = guarded("externalBus", p)
            }, "externalApp")
        }
    }

    BackHandler(canBack) { web.goBack() }

    LaunchedEffect(serverId) { // iroh path info (relay/direct, rtt) for the bar, polled while connected
        while (true) {
            onInfo(withContext(Dispatchers.IO) { state.live.get()?.tunnel?.let { runCatching { it.connInfo() }.getOrNull() } })
            kotlinx.coroutines.delay(2_000)
        }
    }

    DisposableEffect(owner, serverId) {
        fun pause() {
            state.job?.cancel()
            state.job = null
            if (modern && state.script != null) {
                state.script?.remove()
                state.script = null
                WebViewCompat.removeWebMessageListener(web, NATIVE)
            }
            state.live.getAndSet(null)?.let { l -> Thread { runCatching { l.close() } }.start() }
        }
        fun start() {
                error = null
                state.job = scope.launch {
                    // Blocking uniffi calls can't be cancelled: they run to completion, then we close if paused meanwhile.
                    try {
                        withContext(NonCancellable + Dispatchers.IO) {
                            val s = app.store.servers.value.first { it.id == serverId }
                            val token = s.refreshToken ?: throw ReloginRequired()
                            val tunnel = app.core().connect(BridgeInfo(s.bridgeInfoName, s.bridgeInfoJson))
                            try {
                                state.live.set(Live(tunnel, tunnel.startLoopbackProxy(), HaClient(tunnel.okHttpClient(), BASE_URL, token, tunnel::disconnect)))
                            } catch (t: Throwable) { tunnel.disconnect(); throw t }
                        }
                    } catch (e: CancellationException) { throw e
                    } catch (e: ReloginRequired) { swipe.isRefreshing = false; onRelogin(); return@launch
                    } catch (e: Exception) { swipe.isRefreshing = false; error = "Connect failed: ${e.userMessage()}"; return@launch }
                    if (!isActive) {
                        state.live.getAndSet(null)?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } }
                        return@launch
                    }
                    val live = state.live.get() ?: return@launch
                    if (modern) {
                        val rules = setOf(live.origin)
                        WebViewCompat.addWebMessageListener(web, NATIVE, rules) { _, msg, _, isMainFrame, _ ->
                            if (isMainFrame) runCatching { json.parseToJsonElement(msg.data ?: "").jsonObject }.getOrNull()?.let { m ->
                                val method = m["method"]?.jsonPrimitive?.content
                                val payload = m["payload"]?.jsonPrimitive?.content
                                if (method == "canScrollUp") state.canScrollUp = payload == "true"
                                else if (method != null && payload != null) scope.launch { handle(method, payload) }
                            }
                        }
                        state.script = WebViewCompat.addDocumentStartJavaScript(web, SHIM, rules)
                    }
                    Log.d(TAG, "proxy port=${live.proxy.port()} modern=$modern")
                    web.loadUrl("${live.origin}/?external_auth=1")
                }
        }
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { web.onResume(); start() }
            else if (e == Lifecycle.Event.ON_PAUSE) { pause(); web.onPause() }
        }
        swipe.setOnRefreshListener { pause(); start() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); swipe.setOnRefreshListener(null); pause() }
    }
    DisposableEffect(web) { onDispose { (swipe.parent as? ViewGroup)?.removeView(swipe); swipe.removeView(web); web.destroy() } }

    Box(modifier) {
        AndroidView({ swipe }, Modifier.fillMaxSize())
        error?.let { Text(it, Modifier.align(androidx.compose.ui.Alignment.Center)) }
    }
}

private val PATH = Regex("""(ip|relay):\S+ (\d+)ms(?: \(\+(\d+) paths?\))?""")

/** `Tunnel.connInfo()` -> "Direct · 2 ms" / "Relay · 40 ms" (+ " · 2 paths" when there are alternatives). */
fun pathLabel(raw: String): String {
    val m = PATH.matchEntire(raw) ?: return raw.replaceFirstChar { it.uppercase() }
    val (kind, rtt, extra) = m.destructured
    return (if (kind == "ip") "Direct" else "Relay") + " · $rtt ms" + if (extra.isEmpty()) "" else " · ${extra.toInt() + 1} paths"
}

/** Top bar: server name (tap = server switcher), path info subtitle, overflow -> Settings. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DashboardBar(servers: List<Server>, activeId: String, info: String?, onPick: (Server) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var pick by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Box {
                // long-press: the raw iroh path (address, rtt) for debugging
                Column(Modifier.combinedClickable(onLongClick = { info?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }) { pick = true }) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(servers.firstOrNull { it.id == activeId }?.name ?: "?", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    Text(info?.let(::pathLabel) ?: "Connecting…", style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(pick, { pick = false }) {
                    servers.forEach { s -> DropdownMenuItem({ Text(s.name) }, { pick = false; onPick(s) }) }
                }
            }
        },
        actions = {
            Box {
                IconButton({ more = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(more, { more = false }) { DropdownMenuItem({ Text("Settings") }, { more = false; onSettings() }) }
            }
        },
    )
}
