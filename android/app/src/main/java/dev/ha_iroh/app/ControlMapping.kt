package dev.ha_iroh.app

import android.service.controls.DeviceTypes as T
import android.service.controls.templates.TemperatureControlTemplate as TT
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

/** What to render for an entity (SPEC §8.4). Pure data: no android.service.controls objects, so JVM-testable. */
sealed class Spec(val deviceType: Int, val authRequired: Boolean = false) {
    data class Toggle(val on: Boolean, val type: Int, val auth: Boolean = false) : Spec(type, auth)
    data class Range(val value: Float, val min: Float, val max: Float, val step: Float, val type: Int) : Spec(type)
    data class ToggleRange(val on: Boolean, val range: Range) : Spec(range.type)
    data class Temperature(val target: Float, val min: Float, val max: Float, val step: Float, val mode: Int, val modes: Int) : Spec(T.TYPE_THERMOSTAT)
    object Stateless : Spec(T.TYPE_ROUTINE)
}

data class ServiceCall(val domain: String, val service: String, val data: JsonObject = JsonObject(emptyMap()))

private const val FAN_SET_SPEED = 1
private const val COVER_SET_POSITION = 4

private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.floatOrNull
private fun JsonObject.feature(bit: Int) = ((this["supported_features"] as? JsonPrimitive)?.intOrNull ?: 0) and bit != 0
fun domainOf(entityId: String) = entityId.substringBefore('.')
private fun JsonObject.pos(k: String) = num(k)?.takeIf { it > 0 }
private fun pct(v: Float, step: Float, type: Int) = Spec.Range(v, 0f, 100f, step, type)

private val coverTypes = mapOf("garage" to T.TYPE_GARAGE, "door" to T.TYPE_DOOR, "gate" to T.TYPE_GATE, "shutter" to T.TYPE_SHUTTER,
    "window" to T.TYPE_WINDOW, "awning" to T.TYPE_AWNING, "curtain" to T.TYPE_CURTAIN) // blind, shade, others: BLINDS

private val hvacModes = mapOf("off" to TT.MODE_OFF, "heat" to TT.MODE_HEAT, "cool" to TT.MODE_COOL, "heat_cool" to TT.MODE_HEAT_COOL)

private fun modeFlag(mode: Int) = if (mode == TT.MODE_UNKNOWN) 0 else 1 shl mode

/** null = domain not supported. */
fun EntityState.toSpec(): Spec? {
    val on = state == "on"
    return when (domainOf(entityId)) {
        "light" -> if (lightTemplateKind(attributes) == LightKind.TOGGLE) Spec.Toggle(on, T.TYPE_LIGHT)
            else Spec.ToggleRange(on, pct(brightnessPct(attributes).toFloat(), 1f, T.TYPE_LIGHT))
        "switch" -> Spec.Toggle(on, T.TYPE_SWITCH)
        "input_boolean" -> Spec.Toggle(on, T.TYPE_GENERIC_ON_OFF)
        "fan" -> if (attributes.feature(FAN_SET_SPEED))
            Spec.ToggleRange(on, pct(attributes.num("percentage") ?: 0f, attributes.pos("percentage_step") ?: 1f, T.TYPE_FAN))
            else Spec.Toggle(on, T.TYPE_FAN)
        "cover" -> {
            val type = coverTypes[(attributes["device_class"] as? JsonPrimitive)?.contentOrNull] ?: T.TYPE_BLINDS
            if (attributes.feature(COVER_SET_POSITION)) pct(attributes.num("current_position") ?: 0f, 1f, type)
            else Spec.Toggle(state == "open", type)
        }
        "lock" -> Spec.Toggle(state == "locked", T.TYPE_LOCK, auth = true)
        "climate" -> {
            val mode = hvacModes[state] ?: TT.MODE_UNKNOWN
            val flags = (attributes["hvac_modes"] as? JsonArray).orEmpty().mapNotNull { hvacModes[it.jsonPrimitive.contentOrNull] }
                .fold(modeFlag(mode)) { f, m -> f or modeFlag(m) }
            val min = attributes.num("min_temp") ?: 7f
            val max = maxOf(attributes.num("max_temp") ?: 35f, min) // bad registry data must not throw in RangeTemplate
            Spec.Temperature(attributes.num("temperature") ?: min, min, max, attributes.pos("target_temp_step") ?: 0.5f, mode, flags)
        }
        "scene", "script", "button" -> Spec.Stateless
        else -> null
    }
}

/** Domain has a Device Controls mapping (stale selections of dropped domains, e.g. sensor, are not published). */
fun isControllable(entityId: String) = EntityState(entityId, "").toSpec() != null

/** `unavailable` / `unknown` -> STATUS_ERROR text. Scenes/scripts/buttons are `unknown` until first used, so only `unavailable` counts. */
fun EntityState.errorText() = state.takeIf { it == "unavailable" || (it == "unknown" && toSpec() != Spec.Stateless) }

/** Service for a panel action: [on] = toggle/tap, [value] = slider, [mode] = TemperatureControlTemplate mode. null = reject. */
fun actionFor(entityId: String, on: Boolean? = null, value: Float? = null, mode: Int? = null): ServiceCall? {
    fun data(vararg p: Pair<String, JsonElement>) = JsonObject(mapOf(*p))
    val domain = domainOf(entityId)
    return when (domain) {
        "light" -> when {
            value != null -> ServiceCall(domain, "turn_on", data("brightness_pct" to JsonPrimitive(value.roundToInt())))
            on != null -> ServiceCall(domain, if (on) "turn_on" else "turn_off")
            else -> null
        }
        "switch", "input_boolean" -> on?.let { ServiceCall("homeassistant", if (it) "turn_on" else "turn_off") }
        "fan" -> when {
            value != null -> ServiceCall(domain, "set_percentage", data("percentage" to JsonPrimitive(value.roundToInt())))
            on != null -> ServiceCall(domain, if (on) "turn_on" else "turn_off")
            else -> null
        }
        "cover" -> when {
            value != null -> ServiceCall(domain, "set_cover_position", data("position" to JsonPrimitive(value.roundToInt())))
            on != null -> ServiceCall(domain, if (on) "open_cover" else "close_cover")
            else -> null
        }
        "lock" -> on?.let { ServiceCall(domain, if (it) "lock" else "unlock") }
        "climate" -> when {
            value != null -> ServiceCall(domain, "set_temperature", data("temperature" to JsonPrimitive(Math.round(value * 100) / 100.0)))
            mode != null -> hvacModes.entries.firstOrNull { it.value == mode }?.let {
                ServiceCall(domain, "set_hvac_mode", data("hvac_mode" to JsonPrimitive(it.key)))
            }
            else -> null
        }
        "scene", "script" -> on?.let { ServiceCall(domain, "turn_on") }
        "button" -> on?.let { ServiceCall(domain, "press") }
        else -> null
    }
}
