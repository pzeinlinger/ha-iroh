package dev.ha_iroh.app

import android.content.Context
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import uniffi.ha_iroh_core.BridgeInfo
import uniffi.ha_iroh_core.Core

@Serializable
data class Server(
    val id: String,
    val name: String,
    val bridgeInfoName: String,
    val bridgeInfoJson: String,
    val refreshToken: String? = null,
    val selectedEntities: List<String> = emptyList(),
    val pipelineId: String? = null,
    val isDefaultAssist: Boolean = false,
) {
    override fun toString() = "Server(id=$id, name=$name)" // keep the refresh token out of logs
}

/** Whole list as one JSON blob, Tink AES-GCM (key in Android Keystore) in filesDir. All IO is on Dispatchers.IO. */
class ServerStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "servers.bin")
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(ctx, "tink_keyset", "tink_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://ha_iroh_master")
            .build().keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
    private val lock = Any()
    private var loaded = false
    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers

    /** Idempotent; call before reading [servers]. */
    suspend fun init() = locked { }

    private suspend fun <T> locked(f: () -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!loaded) { _servers.value = load(); loaded = true }
            f()
        }
    }

    // Unreadable (e.g. restored without the Keystore key): start empty.
    private fun load(): List<Server> = try {
        if (!file.exists()) emptyList() else json.decodeFromString(String(aead.decrypt(file.readBytes(), AD)))
    } catch (e: Exception) {
        Log.w("ServerStore", "unreadable server list, resetting", e)
        file.delete()
        emptyList()
    }

    private fun save(list: List<Server>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(aead.encrypt(json.encodeToString(list).toByteArray(), AD))
        if (!tmp.renameTo(file)) throw IOException("could not write server list")
        _servers.value = list
    }

    suspend fun upsert(s: Server) = locked {
        val l = _servers.value
        save(if (l.any { it.id == s.id }) l.map { if (it.id == s.id) s else it } else l + s)
    }

    suspend fun remove(id: String) = locked { save(_servers.value.filter { it.id != id }) }

    private companion object { val AD = "servers".toByteArray() }
}

/** Lazily creates one HaClient (and its tunnel) per server. */
class ServerManager(private val core: () -> Core) {
    private val clients = mutableMapOf<String, HaClient>()

    /** Always a fresh tunnel: simplest way to recover from a dead one. */
    suspend fun client(s: Server): HaClient = withContext(Dispatchers.IO) {
        release(s.id)
        val token = s.refreshToken ?: throw ReloginRequired()
        val tunnel = core().connect(BridgeInfo(s.bridgeInfoName, s.bridgeInfoJson))
        HaClient(tunnel.okHttpClient(), BASE_URL, token, tunnel::disconnect)
            .also { synchronized(clients) { clients[s.id] = it } }
    }

    fun release(id: String) {
        synchronized(clients) { clients.remove(id) }?.close()
    }
}
