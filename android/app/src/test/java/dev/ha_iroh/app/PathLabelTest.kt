package dev.ha_iroh.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PathLabelTest {
    @Test fun formatsIrohPathInfo() {
        assertEquals("Direct · 2 ms", pathLabel("ip:10.1.1.1:5000 2ms"))
        assertEquals("Relay · 40 ms · 2 paths", pathLabel("relay:https://euc1-1.relay.n0.iroh.link./ 40ms (+1 path)"))
        assertEquals("Direct · 0 ms · 3 paths", pathLabel("ip:[fe80::1]:1 0ms (+2 paths)"))
        assertEquals("No path", pathLabel("no path"))
    }
}
