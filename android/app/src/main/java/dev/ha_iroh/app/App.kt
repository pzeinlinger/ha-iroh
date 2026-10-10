package dev.ha_iroh.app

import android.app.Application
import uniffi.ha_iroh_core.Core
import uniffi.ha_iroh_core.RelayConfig

class App : Application() {
    /** Created lazily (binds an endpoint, so call from IO). Reset after `wipeKey`. */
    @Volatile private var _core: Core? = null

    @Synchronized
    fun core(): Core = _core ?: Core(filesDir.path, RelayConfig.N0).also { _core = it }

    @Synchronized
    fun resetCore() { _core = null }
}
