package dev.ha_iroh.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlin.concurrent.thread
import uniffi.ha_iroh_core.Core
import uniffi.ha_iroh_core.RelayConfig

class App : Application() {
    val store by lazy { ServerStore(this) }
    val manager by lazy { ServerManager(::core) }

    /** Created lazily (binds an endpoint, so call from IO). Reset after `wipeKey`. */
    @Volatile private var _core: Core? = null

    @Synchronized
    fun core(): Core = _core ?: Core(filesDir.path, relay()).also { _core = it }

    override fun onCreate() {
        super.onCreate()
        // Process-wide, never unregistered. Android gives iroh no netlink, so tell it about changes.
        var lastTransports = -1
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = hint()
            override fun onLost(network: Network) = hint()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val t = listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_VPN)
                    .fold(0) { m, tr -> if (caps.hasTransport(tr)) m or (1 shl tr) else m }
                if (t != lastTransports) { lastTransports = t; hint() }
            }
        })
    }

    /** Skips if no Core yet; may race with [wipeCore]. */
    private fun hint() {
        val c = _core ?: return
        thread { try { c.networkChange() } catch (e: Exception) { Log.w("App", "networkChange", e) } }
    }

    val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    /** Relay override (plain prefs, not secret); read when [core] is constructed, so it applies on next app start. */
    private fun relay(): RelayConfig = parseRelayUrls(prefs.getString("relay_urls", "").orEmpty()).let { if (it.isEmpty()) RelayConfig.N0 else RelayConfig.Custom(it) }

    /** Wipes the app key (even if no Core exists yet) and drops the Core. Call from IO. */
    @Synchronized
    fun wipeCore() { (_core ?: Core(filesDir.path, relay())).wipeKey(); _core = null }
}

/** Comma/newline separated URLs; blank entries dropped. */
fun parseRelayUrls(s: String): List<String> = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
