package dev.ha_iroh.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.service.controls.templates.ToggleTemplate
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.jdk9.asPublisher
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlin.math.roundToInt

fun Context.entityCache(serverId: String) = EntityCache(File(filesDir, "entities-$serverId.json"))

private fun EntityState.title() = (attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull ?: entityId

/** Light only for now (ticket 07). [error] overrides the state (offline, removed, ...). */
fun EntityState.toControl(serverId: String, serverName: String, pi: PendingIntent, stale: Boolean = false, error: String? = null): Control {
    val b = Control.StatefulBuilder(ControlId(serverId, entityId).toString(), pi)
        .setTitle(title()).setStructure(serverName).setDeviceType(DeviceTypes.TYPE_LIGHT)
    val bad = error ?: state.takeIf { it == "unavailable" || it == "unknown" }
    if (bad != null) return b.setStatus(Control.STATUS_ERROR).setStatusText(bad).build()
    val on = state == "on"
    val button = ControlButton(on, "toggle")
    val template = when (lightTemplateKind(attributes)) {
        LightKind.TOGGLE -> ToggleTemplate(entityId, button)
        LightKind.TOGGLE_RANGE -> ToggleRangeTemplate(entityId, button, RangeTemplate(entityId, 0f, 100f, brightnessPct(attributes).toFloat(), 1f, "%.0f%%"))
    }
    return b.setStatus(Control.STATUS_OK)
        .setStatusText((if (on) "On" else "Off") + if (stale) " (cached)" else "")
        .setControlTemplate(template).build()
}

/** Same identity as [toControl], minus state (copy-constructor keeps id/title/structure/type/intent). */
private fun EntityState.toStateless(serverId: String, serverName: String, pi: PendingIntent) =
    Control.StatelessBuilder(toControl(serverId, serverName, pi)).build()

class ControlsService : ControlsProviderService() {
    private val app get() = application as App
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val live = ConcurrentHashMap<String, Session>()
    private val pi by lazy {
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE) // placeholder: opens the app
    }

    override fun onDestroy() {
        scope.cancel() // flows' finally blocks close sessions + tunnels
        super.onDestroy()
    }

    override fun createPublisherForAllAvailable(): java.util.concurrent.Flow.Publisher<Control> = flow {
        app.store.init()
        for (s in app.store.servers.value) {
            val cache = entityCache(s.id).load()
            s.selectedEntities.forEach { emit((cache[it] ?: EntityState(it, "unknown")).toStateless(s.id, s.name, pi)) }
        }
    }.asPublisher(scope.coroutineContext)

    override fun createPublisherFor(controlIds: List<String>): java.util.concurrent.Flow.Publisher<Control> = flow {
        app.store.init()
        val servers = app.store.servers.value
        val byServer = controlIds.groupBy { ControlId.parse(it).serverId }
        // favourites of a removed server would otherwise sit on "Loading..." forever
        byServer.filterKeys { sid -> servers.none { it.id == sid } }.values.flatten().forEach {
            emit(Control.StatefulBuilder(it, pi).setTitle(ControlId.parse(it).entityId).setStatus(Control.STATUS_NOT_FOUND).build())
        }
        emitAll(byServer.mapNotNull { (sid, ids) ->
            servers.firstOrNull { it.id == sid }?.let { serverControls(it, ids.map { id -> ControlId.parse(id).entityId }) }
        }.merge())
    }.asPublisher(scope.coroutineContext)

    /** Cached (stale) first, then live. Failure only affects this server's controls. */
    private fun serverControls(server: Server, entityIds: List<String>): Flow<Control> = flow {
        val cache = entityCache(server.id)
        var states = cache.load()
        fun controls(stale: Boolean = false, error: String? = null) = entityIds.mapNotNull { id ->
            (states[id] ?: if (error != null) EntityState(id, "unknown") else null)?.toControl(server.id, server.name, pi, stale, error)
        }
        controls(stale = true).forEach { emit(it) }
        var client: HaClient? = null
        var session: Session? = null
        try {
            client = app.manager.client(server)
            session = client.connect()
            live[server.id] = session
            val cmd = buildJsonObject { put("type", "subscribe_entities"); putJsonArray("entity_ids") { entityIds.forEach { add(it) } } }
            session.subscribe(cmd).collect { ev ->
                val before = states
                states = EntityDiff.apply(states, ev)
                cache.save(states)
                controls().forEach { emit(it) }
                // removed in HA: tell the panel once instead of leaving it stale
                entityIds.filter { it in before && it !in states }.forEach {
                    emit(EntityState(it, "unknown").toControl(server.id, server.name, pi, error = "removed"))
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            Log.w("ControlsService", "server ${server.id} failed", e)
            val msg = when (e) {
                is ReloginRequired -> "re-login required"
                is HaError -> e.message
                is IOException, is TimeoutCancellationException, is uniffi.ha_iroh_core.CoreException -> "offline"
                else -> "error"
            }
            controls(error = msg).forEach { emit(it) }
        } finally {
            session?.let { live.remove(server.id, it) }
            client?.close() // closes session + tunnel
        }
    }

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        scope.launch {
            var result = ControlAction.RESPONSE_FAIL
            try {
                val (sid, eid) = ControlId.parse(controlId)
                val cmd = when (action) {
                    is BooleanAction -> lightCall(if (action.newState) "turn_on" else "turn_off", eid)
                    is FloatAction -> lightCall("turn_on", eid, action.newValue.roundToInt())
                    else -> error("unsupported action")
                }
                val session = live[sid]
                if (session != null) session.sendCommand(cmd) else {
                    app.store.init()
                    val client = app.manager.client(app.store.servers.value.first { it.id == sid })
                    try { client.connect().sendCommand(cmd) } finally { client.close() }
                }
                result = ControlAction.RESPONSE_OK
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ControlsService", "action failed", e)
            } finally {
                consumer.accept(result) // FAIL unless the call succeeded
            }
        }
    }

    private fun lightCall(service: String, entityId: String, pct: Int? = null) = buildJsonObject {
        put("type", "call_service"); put("domain", "light"); put("service", service)
        putJsonObject("target") { put("entity_id", entityId) }
        if (pct != null) putJsonObject("service_data") { put("brightness_pct", pct) }
    }
}
