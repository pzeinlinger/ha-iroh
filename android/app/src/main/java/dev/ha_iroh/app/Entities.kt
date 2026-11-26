package dev.ha_iroh.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

@Serializable
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
)

fun EntityState.title() = (attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull ?: entityId

/** Control id on the wire: "serverId:entityId" (UUIDs and entity ids contain no colon). */
data class ControlId(val serverId: String, val entityId: String) {
    override fun toString() = "$serverId:$entityId"
    companion object {
        fun parse(s: String) = ControlId(s.substringBefore(':'), s.substringAfter(':'))
    }
}

/** `subscribe_entities` compressed diffs (HA websocket_api/messages.py): `a` add, `c` change (`+` merge, `-` attr removal), `r` remove. */
object EntityDiff {
    fun apply(current: Map<String, EntityState>, event: JsonObject): Map<String, EntityState> {
        val m = current.toMutableMap()
        event["a"]?.jsonObject?.forEach { (id, v) ->
            val o = v.jsonObject
            m[id] = EntityState(id, o["s"]!!.jsonPrimitive.content, o["a"]?.jsonObject ?: JsonObject(emptyMap()))
        }
        event["c"]?.jsonObject?.forEach { (id, v) ->
            var e = m[id] ?: return@forEach // change for an entity we never saw: ignore
            v.jsonObject["+"]?.jsonObject?.let { p ->
                e = e.copy(
                    state = p["s"]?.jsonPrimitive?.content ?: e.state,
                    attributes = JsonObject(e.attributes + (p["a"]?.jsonObject ?: emptyMap())),
                )
            }
            v.jsonObject["-"]?.jsonObject?.get("a")?.jsonArray?.let { gone ->
                val names = gone.map { it.jsonPrimitive.content }.toSet()
                e = e.copy(attributes = JsonObject(e.attributes.filterKeys { it !in names }))
            }
            m[id] = e
        }
        event["r"]?.jsonArray?.forEach { m.remove(it.jsonPrimitive.content) }
        return m
    }

    /** `get_states` result message -> entities. */
    fun parseStates(result: JsonObject): Map<String, EntityState> = result["result"]!!.jsonArray.associate {
        val o = it.jsonObject
        val id = o["entity_id"]!!.jsonPrimitive.content
        id to EntityState(id, o["state"]!!.jsonPrimitive.content, o["attributes"]?.jsonObject ?: JsonObject(emptyMap()))
    }
}

enum class LightKind { TOGGLE, TOGGLE_RANGE }

fun lightTemplateKind(attrs: JsonObject): LightKind {
    val modes = attrs["supported_color_modes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    return if (modes.any { it != "onoff" } || attrs.containsKey("brightness")) LightKind.TOGGLE_RANGE else LightKind.TOGGLE
}

/** HA brightness 0..255 -> 0..100; absent/null (light off) -> 0. */
fun brightnessPct(attrs: JsonObject): Int =
    ((attrs["brightness"] as? JsonPrimitive)?.doubleOrNull?.let { it / 255 * 100 } ?: 0.0).roundToInt()

/** Last known states of one server as a plain JSON file (not secret). All IO is blocking; call from IO. */
class EntityCache(private val file: File) {
    private val ser = MapSerializer(String.serializer(), EntityState.serializer())

    fun load(): Map<String, EntityState> =
        runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyMap())

    fun save(m: Map<String, EntityState>) = writeAtomic(file, json.encodeToString(ser, m))

}

private val writeLock = Any()

/** Unique temp file + rename; the lock is global because callers make a new instance per call. */
fun writeAtomic(file: File, text: String) = synchronized(writeLock) {
    val tmp = File.createTempFile("tmp", ".part", file.absoluteFile.parentFile)
    try {
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw IOException("could not write ${file.name}")
    } finally { tmp.delete() }
}

/** entity_id -> area name; entity's own area wins, else its device's. */
fun areaMap(areas: JsonArray, entities: JsonArray, devices: JsonArray): Map<String, String> {
    fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    val names = areas.associate { it.jsonObject.s("area_id") to it.jsonObject.s("name") }
    val deviceArea = devices.associate { it.jsonObject.s("id") to it.jsonObject.s("area_id") }
    return entities.mapNotNull {
        val o = it.jsonObject
        val name = names[o.s("area_id") ?: deviceArea[o.s("device_id")]] ?: return@mapNotNull null
        o.s("entity_id")!! to name
    }.toMap()
}

/** Entities Device Controls hides: registry entry has entity_category (config/diagnostic), hidden_by or disabled_by. */
fun hiddenEntities(entities: JsonArray): Set<String> = entities.mapNotNull {
    val o = it.jsonObject
    fun set(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull != null
    if (set("entity_category") || set("hidden_by") || set("disabled_by")) (o["entity_id"] as? JsonPrimitive)?.contentOrNull else null
}.toSet()

/** What the registries tell us about entities; one persisted file per server. */
@Serializable
data class Registry(val areas: Map<String, String> = emptyMap(), val hidden: Set<String> = emptySet())

/** Best-effort: fetch the three registries and persist [Registry] in [file]; returns it (the stored one on any failure). */
suspend fun Session.refreshAreas(file: File): Registry = try {
    suspend fun list(t: String) = sendCommand(buildJsonObject { put("type", t) })["result"]!!.jsonArray
    val entities = list("config/entity_registry/list")
    Registry(areaMap(list("config/area_registry/list"), entities, list("config/device_registry/list")), hiddenEntities(entities))
        .also { writeAtomic(file, json.encodeToString(it)) }
} catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { loadAreas(file) }

fun loadAreas(file: File): Registry = runCatching { json.decodeFromString<Registry>(file.readText()) }.getOrDefault(Registry())

/** What Device Controls offers: every entity with a mapping, minus registry-hidden (config/diagnostic, hidden, disabled). */
fun controllable(states: Map<String, EntityState>, reg: Registry) = states.values.filter { isControllable(it.entityId) && it.entityId !in reg.hidden }
