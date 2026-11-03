package dev.stride.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class OdometerTest {
    /** The board resets its own counter mid-session now and then. */
    @Test fun resetKeepsCountingForward() {
        val o = Odometer()
        listOf(0.0, 1200.0, 2400.0, 0.0, 5.0, 300.0).forEach { o.update(it) }
        assertEquals(2700.0, o.total, 1e-9)
    }

    @Test fun neverGoesBackwards() {
        val o = Odometer()
        var last = 0.0
        listOf(100.0, 50.0, 60.0, 0.0, 10.0).forEach {
            val t = o.update(it)
            assert(t >= last) { "went back from $last to $t" }
            last = t
        }
    }
}
