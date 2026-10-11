package dev.ha_iroh.app

import android.app.Application
import uniffi.ha_iroh_core.Core
import uniffi.ha_iroh_core.RelayConfig

class App : Application() {
    val store by lazy { ServerStore(this) }
    val manager by lazy { ServerManager(::core) }

    /** Created lazily (binds an endpoint, so call from IO). Reset after `wipeKey`. */
    @Volatile private var _core: Core? = null

    @Synchronized
    fun core(): Core = _core ?: Core(filesDir.path, relay()).also { _core = it }

    val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    /** Relay override (plain prefs, not secret); read when [core] is constructed, so it applies on next app start. */
    private fun relay(): RelayConfig = parseRelayUrls(prefs.getString("relay_urls", "").orEmpty()).let { if (it.isEmpty()) RelayConfig.N0 else RelayConfig.Custom(it) }

    /** Wipes the app key (even if no Core exists yet) and drops the Core. Call from IO. */
    @Synchronized
    fun wipeCore() { (_core ?: Core(filesDir.path, relay())).wipeKey(); _core = null }
}

/** Comma/newline separated URLs; blank entries dropped. */
fun parseRelayUrls(s: String): List<String> = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
