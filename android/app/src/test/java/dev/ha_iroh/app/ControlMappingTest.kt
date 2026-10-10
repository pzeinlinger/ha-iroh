package dev.ha_iroh.app

import android.service.controls.DeviceTypes as T
import android.service.controls.templates.TemperatureControlTemplate as TT
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlMappingTest {
    private fun e(id: String, state: String, attrs: String = "{}") =
        EntityState(id, state, json.parseToJsonElement(attrs).jsonObject)

    private fun call(domain: String, service: String, data: String = "{}") =
        ServiceCall(domain, service, json.parseToJsonElement(data).jsonObject)

    @Test fun light() {
        val s = e("light.a", "on", """{"brightness":128,"supported_color_modes":["brightness"]}""").toSpec()
        assertEquals(Spec.ToggleRange(true, Spec.Range(50f, 0f, 100f, 1f, T.TYPE_LIGHT)), s)
        assertEquals(Spec.Toggle(false, T.TYPE_LIGHT), e("light.a", "off", """{"supported_color_modes":["onoff"]}""").toSpec())
        assertEquals(call("light", "turn_on"), actionFor("light.a", on = true))
        assertEquals(call("light", "turn_off"), actionFor("light.a", on = false))
        assertEquals(call("light", "turn_on", """{"brightness_pct":40}"""), actionFor("light.a", value = 40.2f))
    }

    @Test fun unavailableLightKeepsTypeAndReportsError() {
        val l = e("light.a", "unavailable")
        assertEquals(T.TYPE_LIGHT, l.toSpec()!!.deviceType)
        assertEquals("unavailable", l.errorText())
        assertNull(e("light.a", "on").errorText())
        assertEquals("unknown", e("sensor.x", "unknown").errorText())
        assertNull(e("button.x", "unknown").errorText())
        assertEquals("unavailable", e("scene.x", "unavailable").errorText())
    }

    @Test fun switchAndInputBoolean() {
        assertEquals(Spec.Toggle(true, T.TYPE_SWITCH), e("switch.a", "on").toSpec())
        assertEquals(Spec.Toggle(false, T.TYPE_GENERIC_ON_OFF), e("input_boolean.a", "off").toSpec())
        assertEquals(call("homeassistant", "turn_on"), actionFor("switch.a", on = true))
        assertEquals(call("homeassistant", "turn_off"), actionFor("input_boolean.a", on = false))
    }

    @Test fun fan() {
        val s = e("fan.a", "on", """{"supported_features":1,"percentage":33,"percentage_step":33.3}""").toSpec()
        assertEquals(Spec.ToggleRange(true, Spec.Range(33f, 0f, 100f, 33.3f, T.TYPE_FAN)), s)
        assertEquals(Spec.Toggle(true, T.TYPE_FAN), e("fan.a", "on", """{"supported_features":0}""").toSpec())
        assertEquals(call("fan", "turn_off"), actionFor("fan.a", on = false))
        assertEquals(call("fan", "set_percentage", """{"percentage":66}"""), actionFor("fan.a", value = 66f))
    }

    @Test fun coverWithPosition() {
        val s = e("cover.g", "open", """{"device_class":"garage","supported_features":4,"current_position":30}""").toSpec()
        assertEquals(Spec.Range(30f, 0f, 100f, 1f, T.TYPE_GARAGE), s)
        assertEquals(call("cover", "set_cover_position", """{"position":55}"""), actionFor("cover.g", value = 55f))
    }

    @Test fun coverWithoutPosition() {
        assertEquals(Spec.Toggle(true, T.TYPE_BLINDS), e("cover.b", "open", """{"supported_features":3}""").toSpec())
        assertEquals(Spec.Toggle(false, T.TYPE_CURTAIN), e("cover.c", "closed", """{"device_class":"curtain"}""").toSpec())
        assertEquals(call("cover", "open_cover"), actionFor("cover.b", on = true))
        assertEquals(call("cover", "close_cover"), actionFor("cover.b", on = false))
    }

    @Test fun coverDeviceClasses() {
        val exp = mapOf("garage" to T.TYPE_GARAGE, "blind" to T.TYPE_BLINDS, "shade" to T.TYPE_BLINDS, "curtain" to T.TYPE_CURTAIN,
            "door" to T.TYPE_DOOR, "gate" to T.TYPE_GATE, "shutter" to T.TYPE_SHUTTER, "window" to T.TYPE_WINDOW,
            "awning" to T.TYPE_AWNING, "damper" to T.TYPE_BLINDS)
        exp.forEach { (dc, t) -> assertEquals(dc, t, e("cover.x", "open", """{"device_class":"$dc"}""").toSpec()!!.deviceType) }
    }

    @Test fun lock() {
        val s = e("lock.a", "locked").toSpec()!!
        assertEquals(Spec.Toggle(true, T.TYPE_LOCK, auth = true), s)
        assertTrue(s.authRequired)
        assertEquals(call("lock", "lock"), actionFor("lock.a", on = true))
        assertEquals(call("lock", "unlock"), actionFor("lock.a", on = false))
    }

    @Test fun climate() {
        val s = e("climate.a", "heat", """{"temperature":21.5,"min_temp":10,"max_temp":30,"target_temp_step":0.5,"hvac_modes":["off","heat","cool"]}""").toSpec()
        assertEquals(Spec.Temperature(21.5f, 10f, 30f, 0.5f, TT.MODE_HEAT, TT.FLAG_MODE_OFF or TT.FLAG_MODE_HEAT or TT.FLAG_MODE_COOL), s)
        assertEquals(T.TYPE_THERMOSTAT, s!!.deviceType)
        // step defaults to 0.5, off mode
        val d = e("climate.a", "off", """{"temperature":20,"min_temp":7,"max_temp":35}""").toSpec() as Spec.Temperature
        assertEquals(0.5f, d.step)
        assertEquals(TT.MODE_OFF, d.mode)
        assertEquals(call("climate", "set_temperature", """{"temperature":22.5}"""), actionFor("climate.a", value = 22.5f))
        assertEquals(call("climate", "set_hvac_mode", """{"hvac_mode":"cool"}"""), actionFor("climate.a", mode = TT.MODE_COOL))
    }

    @Test fun climateMinAboveMaxDoesNotThrow() {
        val t = e("climate.a", "heat", """{"temperature":20,"min_temp":30,"max_temp":10}""").toSpec() as Spec.Temperature
        assertEquals(30f, t.min); assertEquals(30f, t.max)
    }

    @Test fun nullAttributesDoNotThrow() {
        assertEquals(Spec.ToggleRange(false, Spec.Range(0f, 0f, 100f, 1f, T.TYPE_LIGHT)), e("light.a", "off", """{"brightness":null,"supported_color_modes":["brightness"]}""").toSpec())
        val t = e("climate.a", "off", """{"temperature":null,"min_temp":null,"max_temp":null,"target_temp_step":null}""").toSpec() as Spec.Temperature
        assertEquals(Triple(7f, 7f, 35f), Triple(t.target, t.min, t.max)); assertEquals(0.5f, t.step)
        assertEquals(Spec.Range(0f, 0f, 100f, 1f, T.TYPE_BLINDS), e("cover.a", "open", """{"supported_features":4,"current_position":null}""").toSpec())
        assertEquals(Spec.ToggleRange(true, Spec.Range(0f, 0f, 100f, 1f, T.TYPE_FAN)), e("fan.a", "on", """{"supported_features":1,"percentage":null,"percentage_step":null}""").toSpec())
    }

    @Test fun routines() {
        for (d in listOf("scene", "script", "button")) {
            assertEquals(Spec.Stateless, e("$d.a", "unknown").toSpec())
            assertEquals(T.TYPE_ROUTINE, e("$d.a", "unknown").toSpec()!!.deviceType)
        }
        assertEquals(call("scene", "turn_on"), actionFor("scene.a", on = true))
        assertEquals(call("script", "turn_on"), actionFor("script.a", on = true))
        assertEquals(call("button", "press"), actionFor("button.a", on = true))
    }

    @Test fun readOnly() {
        assertEquals(Spec.ReadOnly("21.5 °C"), e("sensor.t", "21.5", """{"unit_of_measurement":"°C"}""").toSpec())
        assertEquals(Spec.ReadOnly("on"), e("binary_sensor.d", "on").toSpec())
        assertNull(actionFor("sensor.t", on = true))
        assertNull(actionFor("binary_sensor.d", value = 1f))
    }

    @Test fun unsupportedDomain() {
        assertNull(e("vacuum.a", "docked").toSpec())
        assertNull(actionFor("vacuum.a", on = true))
    }
}
