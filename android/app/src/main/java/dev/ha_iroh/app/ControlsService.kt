package dev.ha_iroh.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.CommandAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.ModeAction
import android.service.controls.actions.FloatAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.TemperatureControlTemplate
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

fun Context.entityCache(serverId: String) = EntityCache(File(filesDir, "entities-$serverId.json"))

private fun EntityState.title() = (attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull ?: entityId

fun Context.areaFile(serverId: String) = File(filesDir, "areas-$serverId.json")

private fun Spec.Range.template(id: String) = RangeTemplate(id, min, max, value.coerceIn(min, max), step, "%.0f%%")

/** [error] overrides the state (offline, removed, ...). */
fun EntityState.toControl(serverId: String, serverName: String, pi: PendingIntent, stale: Boolean = false, error: String? = null, area: String? = null): Control {
    val spec = toSpec()
    val b = Control.StatefulBuilder(ControlId(serverId, entityId).toString(), pi)
        .setTitle(title()).setStructure(serverName).setDeviceType(spec?.deviceType ?: DeviceTypes.TYPE_UNKNOWN)
    area?.let { b.setZone(it) }
    if (spec?.authRequired == true && Build.VERSION.SDK_INT >= 33) b.setAuthRequired(true)
    val bad = error ?: errorText()
    if (bad != null || spec == null) return b.setStatus(Control.STATUS_ERROR).setStatusText(bad ?: "unsupported").build()
    fun button(on: Boolean) = ControlButton(on, "toggle")
    val template = when (spec) {
        is Spec.Toggle -> ToggleTemplate(entityId, button(spec.on))
        is Spec.ToggleRange -> ToggleRangeTemplate(entityId, button(spec.on), spec.range.template(entityId))
        is Spec.Range -> spec.template(entityId)
        is Spec.Temperature -> TemperatureControlTemplate(entityId,
            RangeTemplate(entityId, spec.min, spec.max, spec.target.coerceIn(spec.min, spec.max), spec.step, "%.1f\u00b0"),
            spec.mode, spec.mode, spec.modes)
        Spec.Stateless -> StatelessTemplate(entityId)
        is Spec.ReadOnly -> null
    }
    // scene/button state is the last-activated timestamp: noise, so no status text
    val text = when (spec) { is Spec.ReadOnly -> spec.text; Spec.Stateless -> ""; else -> state.replaceFirstChar { it.uppercase() } }
    template?.let { b.setControlTemplate(it) }
    return b.setStatus(Control.STATUS_OK).setStatusText(text + if (stale) " (cached)" else "").build()
}

/** Same identity as [toControl], minus state (copy-constructor keeps id/title/structure/type/intent). */
private fun EntityState.toStateless(serverId: String, serverName: String, pi: PendingIntent, area: String?) =
    Control.StatelessBuilder(toControl(serverId, serverName, pi, area = area)).build()

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
            val areas = loadAreas(areaFile(s.id))
            s.selectedEntities.forEach { emit((cache[it] ?: EntityState(it, "unknown")).toStateless(s.id, s.name, pi, areas[it])) }
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
        var areas = loadAreas(areaFile(server.id))
        fun controls(stale: Boolean = false, error: String? = null) = entityIds.mapNotNull { id ->
            (states[id] ?: if (error != null) EntityState(id, "unknown") else null)?.toControl(server.id, server.name, pi, stale, error, areas[id])
        }
        controls(stale = true).forEach { emit(it) }
        var client: HaClient? = null
        var session: Session? = null
        try {
            client = app.manager.client(server)
            session = client.connect()
            live[server.id] = session
            val cmd = buildJsonObject { put("type", "subscribe_entities"); putJsonArray("entity_ids") { entityIds.forEach { add(it) } } }
            var areasDone = false
            session.subscribe(cmd).collect { ev ->
                val before = states
                states = EntityDiff.apply(states, ev)
                cache.save(states)
                controls().forEach { emit(it) }
                // after the first live state: zones show up from the next emission on, no delay for the state
                if (!areasDone) { areasDone = true; areas = session.refreshAreas(areaFile(server.id)) }
                // removed in HA: tell the panel once instead of leaving it stale
                entityIds.filter { it in before && it !in states }.forEach {
                    emit(EntityState(it, "unknown").toControl(server.id, server.name, pi, error = "removed", area = areas[it]))
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
                val call = when (action) {
                    is BooleanAction -> actionFor(eid, on = action.newState)
                    is FloatAction -> actionFor(eid, value = action.newValue)
                    is CommandAction -> actionFor(eid, on = true) // StatelessTemplate tap
                    is ModeAction -> actionFor(eid, mode = action.newMode)
                    else -> null
                } ?: error("action rejected for $eid") // read-only / unsupported -> RESPONSE_FAIL
                val cmd = buildJsonObject {
                    put("type", "call_service"); put("domain", call.domain); put("service", call.service)
                    putJsonObject("target") { put("entity_id", eid) }
                    if (call.data.isNotEmpty()) put("service_data", call.data)
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
}
