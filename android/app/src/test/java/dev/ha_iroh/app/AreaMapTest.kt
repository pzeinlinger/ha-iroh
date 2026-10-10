package dev.ha_iroh.app

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
}
