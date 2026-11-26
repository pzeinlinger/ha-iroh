package dev.ha_iroh.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AreaMapTest {
    @Test fun entityAreaThenDeviceArea() {
        fun arr(s: String) = json.parseToJsonElement(s).jsonArray
        val m = areaMap(
            arr("""[{"area_id":"k","name":"Kitchen"},{"area_id":"b","name":"Bed"}]"""),
            arr("""[{"entity_id":"light.a","area_id":"k","device_id":"d1"},{"entity_id":"light.b","area_id":null,"device_id":"d1"},
                    {"entity_id":"light.c","area_id":null,"device_id":null}]"""),
            arr("""[{"id":"d1","area_id":"b"}]"""),
        )
        assertEquals(mapOf("light.a" to "Kitchen", "light.b" to "Bed"), m)
    }

    @Test fun hiddenSet() {
        val e = json.parseToJsonElement("""[{"entity_id":"light.a","entity_category":null,"hidden_by":null,"disabled_by":null},
            {"entity_id":"sensor.d","entity_category":"diagnostic"},{"entity_id":"switch.c","entity_category":"config"},
            {"entity_id":"light.h","hidden_by":"user"},{"entity_id":"light.x","disabled_by":"integration"}]""").jsonArray
        assertEquals(setOf("sensor.d", "switch.c", "light.h", "light.x"), hiddenEntities(e))
    }

    @Test fun registryRoundTripsAndOldFileIsIgnored() {
        val f = java.io.File.createTempFile("reg", ".json")
        writeAtomic(f, json.encodeToString(Registry(mapOf("light.a" to "Kitchen"), setOf("light.h"))))
        assertEquals(Registry(mapOf("light.a" to "Kitchen"), setOf("light.h")), loadAreas(f))
        f.writeText("""{"light.a":"Kitchen"}""") // pre-ticket-19 format
        assertEquals(Registry(), loadAreas(f))
        f.delete()
    }

    @Test fun controllableDropsHiddenAndUnmapped() {
        val all = listOf("light.a", "switch.x", "light.h", "sensor.t").associateWith { EntityState(it, "on") }
        assertEquals(listOf("light.a", "switch.x"), controllable(all, Registry(hidden = setOf("light.h"))).map { it.entityId })
    }
}
