package dev.ha_iroh.app

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ha_iroh_core.BridgeInfo
import uniffi.ha_iroh_core.LoopbackProxy
import uniffi.ha_iroh_core.Tunnel
import java.util.concurrent.atomic.AtomicReference

/**
 * HA's own login page in a WebView over a fresh tunnel + loopback proxy (works with any auth provider).
 * The `homeassistant://auth-callback?code=` navigation is intercepted and exchanged; the page never leaves the proxy origin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Login(app: App, server: Server, onCancel: () -> Unit, onDone: () -> Unit) {
    BackHandler(onBack = onCancel)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val st = remember { object { val live = AtomicReference<Pair<Tunnel, LoopbackProxy>?>(null); var origin: String? = null; var exchanging = false } }
    val web = remember {
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
    }

    fun close() { // idempotent; blocking uniffi calls off the main thread
        CookieManager.getInstance().removeAllCookies(null)
        st.live.getAndSet(null)?.let { (t, p) -> Thread { runCatching { p.stop() }; runCatching { t.disconnect() } }.start() }
    }

    fun exchange(code: String) {
        if (st.exchanging) return
        st.exchanging = true
        loading = true
        scope.launch {
            try {
                val tunnel = st.live.get()!!.first
                val refresh = withContext(Dispatchers.IO) { tunnel.okHttpClient().token(BASE_URL, "grant_type" to "authorization_code", "code" to code).str("refresh_token") }
                app.store.upsert(server.copy(refreshToken = refresh))
                close()
                onDone()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { st.exchanging = false; error = "Login failed: ${e.userMessage()}"; loading = false }
        }
    }

    remember(web) {
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.toString().inOrigin(st.origin)) return false
                if (request.isForMainFrame && u.toString().startsWith("$REDIRECT_URI?")) u.getQueryParameter("code")?.let(::exchange)
                return true // never navigate off the proxy origin
            }
            override fun onPageFinished(view: WebView, url: String?) { if (!st.exchanging) loading = false }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                error = "WebView renderer died; go back and try again"; loading = false; return true
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            withContext(NonCancellable + Dispatchers.IO) { // blocking uniffi calls can't be cancelled: let them finish, close below if we were left meanwhile
                val tunnel = app.core().connect(BridgeInfo(server.bridgeInfoName, server.bridgeInfoJson))
                try {
                    val proxy = tunnel.startLoopbackProxy()
                    st.origin = "http://${proxy.token()}.localhost:${proxy.port()}"
                    st.live.set(tunnel to proxy)
                } catch (t: Throwable) { tunnel.disconnect(); throw t }
            }
            ensureActive() // left (Back) while connecting: the catch below closes tunnel + proxy
            web.loadUrl("${st.origin}/auth/authorize?client_id=${android.net.Uri.encode(CLIENT_ID)}&redirect_uri=${android.net.Uri.encode(REDIRECT_URI)}")
        } catch (e: CancellationException) { close(); throw e
        } catch (e: Exception) { error = "Connect failed: ${e.userMessage()}"; loading = false }
    }
    DisposableEffect(web) { onDispose { close(); web.destroy() } }

    Scaffold(topBar = { TopAppBar({ Text("Log in to ${server.name}") }, navigationIcon = { BackArrow() }) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding()) {
            AndroidView({ web }, Modifier.fillMaxSize())
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            error?.let { Text(it, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        }
    }
}
