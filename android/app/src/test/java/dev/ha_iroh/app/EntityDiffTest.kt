package dev.ha_iroh.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EntityDiffTest {
    private fun ev(s: String) = json.parseToJsonElement(s).jsonObject
    private val init = EntityDiff.apply(emptyMap(), ev("""{"a":{
        "light.a":{"s":"on","a":{"brightness":255,"friendly_name":"A","effect":"x"},"c":"ctx","lc":1.5,"lu":1.5},
        "light.b":{"s":"off","a":{"friendly_name":"B"},"c":"ctx","lu":2.0}}}"""))

    @Test fun addTwo() {
        assertEquals(setOf("light.a", "light.b"), init.keys)
        assertEquals("on", init["light.a"]!!.state)
    }

    @Test fun changeMergesAttributes() {
        val m = EntityDiff.apply(init, ev("""{"c":{"light.a":{"+":{"s":"off","a":{"brightness":10},"lc":3.0,"lu":3.0}}}}"""))
        val a = m["light.a"]!!
        assertEquals("off", a.state)
        assertEquals(10, a.attributes["brightness"]!!.jsonPrimitive.int)
        assertEquals("A", a.attributes["friendly_name"]!!.jsonPrimitive.content)
        assertEquals("off", m["light.b"]!!.state)
    }

    @Test fun changeRemovesAttribute() {
        val m = EntityDiff.apply(init, ev("""{"c":{"light.a":{"-":{"a":["effect"]}}}}"""))
        assertFalse(m["light.a"]!!.attributes.containsKey("effect"))
        assertTrue(m["light.a"]!!.attributes.containsKey("brightness"))
        assertEquals("on", m["light.a"]!!.state)
    }

    @Test fun removeEntity() {
        val m = EntityDiff.apply(init, ev("""{"r":["light.b"]}"""))
        assertEquals(setOf("light.a"), m.keys)
    }

    @Test fun addChangeRemoveInOneEvent() {
        val m = EntityDiff.apply(init, ev("""{
            "a":{"light.c":{"s":"on","a":{},"lu":4.0}},
            "c":{"light.c":{"+":{"s":"off"}},"light.a":{"+":{"s":"off"}}},
            "r":["light.b","light.a"]}"""))
        assertEquals(setOf("light.c"), m.keys)
        assertEquals("off", m["light.c"]!!.state)
    }

    @Test fun getStates() {
        val m = EntityDiff.parseStates(ev("""{"result":[{"entity_id":"light.x","state":"on","attributes":{"brightness":5}},{"entity_id":"sensor.y","state":"1","attributes":{}}]}"""))
        assertEquals(setOf("light.x", "sensor.y"), m.keys)
        assertEquals("on", m["light.x"]!!.state)
    }

    private fun attrs(s: String) = ev(s)

    @Test fun lightKind() {
        assertEquals(LightKind.TOGGLE, lightTemplateKind(attrs("""{"supported_color_modes":["onoff"]}""")))
        assertEquals(LightKind.TOGGLE_RANGE, lightTemplateKind(attrs("""{"supported_color_modes":["onoff","brightness"]}""")))
        assertEquals(LightKind.TOGGLE_RANGE, lightTemplateKind(attrs("""{"supported_color_modes":["hs"]}""")))
        assertEquals(LightKind.TOGGLE_RANGE, lightTemplateKind(attrs("""{"brightness":3}""")))
        assertEquals(LightKind.TOGGLE, lightTemplateKind(attrs("""{}""")))
    }

    @Test fun brightnessPct() {
        assertEquals(100, brightnessPct(attrs("""{"brightness":255}""")))
        assertEquals(50, brightnessPct(attrs("""{"brightness":127}""")))
        assertEquals(0, brightnessPct(attrs("""{"brightness":null}""")))
        assertEquals(0, brightnessPct(attrs("""{}""")))
    }
}
