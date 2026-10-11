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

    @Test fun pickerGroupsAreaOrderHiddenAndSearch() {
        fun st(id: String, name: String) = EntityState(id, "on", json.parseToJsonElement("""{"friendly_name":"$name"}""").jsonObject)
        val all = listOf(st("light.b", "Beta"), st("light.a", "alpha"), st("switch.x", "Plug"), st("light.h", "Hidden"), st("light.k", "Kettle"))
        val reg = Registry(mapOf("light.b" to "Kitchen", "light.a" to "Kitchen", "light.k" to "Attic"), setOf("light.h"))
        val g = pickerGroups(all, reg, emptySet(), "")
        assertEquals(listOf("Attic", "Kitchen", NO_AREA), g.map { it.first })
        assertEquals(listOf("light.a", "light.b"), g[1].second.map { it.entityId })
        assertEquals(listOf("switch.x"), g[2].second.map { it.entityId }) // light.h hidden
        assertTrue(pickerGroups(all, reg, setOf("light.h"), "").any { g -> g.second.any { it.entityId == "light.h" } })
        assertEquals(listOf("light.k"), pickerGroups(all, reg, emptySet(), "KET").flatMap { it.second }.map { it.entityId })
    }
}
