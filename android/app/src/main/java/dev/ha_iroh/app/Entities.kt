package dev.ha_iroh.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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

    /** Unique temp file + rename; the lock is global because callers make a new instance per call. */
    fun save(m: Map<String, EntityState>) = synchronized(lock) {
        val tmp = File.createTempFile("entities", ".tmp", file.absoluteFile.parentFile)
        try {
            tmp.writeText(json.encodeToString(ser, m))
            if (!tmp.renameTo(file)) throw IOException("could not write ${file.name}")
        } finally { tmp.delete() }
    }

    private companion object { val lock = Any() }
}
