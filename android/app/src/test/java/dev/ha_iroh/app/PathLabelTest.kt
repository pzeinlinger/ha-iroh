package dev.ha_iroh.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PathLabelTest {
    @Test fun formatsIrohPaths() {
        assertEquals("10.1.1.1:5000 · 2 ms", pathLabel("ip:10.1.1.1:5000 2ms"))
        assertEquals("[fe80::1]:1 · 0 ms · active", pathLabel("* ip:[fe80::1]:1 0ms"))
        assertEquals("relay euc1-1.relay.n0.iroh.link · 40 ms", pathLabel("relay:https://euc1-1.relay.n0.iroh.link./ 40ms"))
        assertEquals("garbage", pathLabel("garbage"))
    }

    @Test fun summarisesSelectedPath() {
        assertEquals("No path", pathSummary(emptyList()))
        assertEquals("Direct · 2 ms", pathSummary(listOf("* ip:10.1.1.1:5000 2ms")))
        assertEquals("Direct · 2 ms · 2 paths", pathSummary(listOf("relay:https://r/ 40ms", "* ip:10.1.1.1:5000 2ms")))
        assertEquals("Relay · 40 ms", pathSummary(listOf("relay:https://r/ 40ms")))
    }
}
