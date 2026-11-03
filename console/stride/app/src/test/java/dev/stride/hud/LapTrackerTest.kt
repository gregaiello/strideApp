package dev.stride.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LapTrackerTest {

    private fun started(): LapTracker = LapTracker().apply { start(0, 0.0, 0.0, 0.0) }

    @Test fun markBeforeStartIsIgnored() {
        val t = LapTracker()
        assertNull(t.mark(10_000, 10.0, 50.0))
        assertTrue(t.closed().isEmpty())
    }

    @Test fun firstLapStartsAtStart() {
        val t = started()
        for (s in 1..60) t.sample(s * 1000L, s.toDouble(), s * 250.0 / 60, 0, 0.0)
        val lap = t.mark(60_000, 60.0, 250.0)!!
        assertEquals(1, lap.index)
        assertEquals(60.0, lap.activeSec, 1e-9)
        assertEquals(250.0, lap.distanceM, 1e-9)
        assertEquals(0L, lap.startMs)
        assertEquals(60_000L, lap.endMs)
        assertEquals(2, t.live(61.0, 253.0)!!.index)
    }

    @Test fun doubleTapWithinTwoSecondsIgnored() {
        val t = started()
        assertNotNull(t.mark(60_000, 60.0, 250.0))
        assertNull(t.mark(61_500, 61.5, 256.0))
        val second = t.mark(62_100, 62.1, 258.0)!!
        assertEquals(2, second.index)
        assertEquals(2.1, second.activeSec, 1e-9)
    }

    @Test fun pausedTimeIsNotLapTime() {
        val t = started()
        for (s in 1..30) t.sample(s * 1000L, s.toDouble(), s * 2.0, 0, 0.0)
        // Paused: the wall clock runs on for a minute, active time does not.
        for (s in 31..90) t.sample(s * 1000L, 30.0, 60.0, 0, 0.0)
        for (s in 91..120) t.sample(s * 1000L, (s - 60).toDouble(), (s - 60) * 2.0, 0, 0.0)
        val lap = t.mark(120_000, 60.0, 120.0)!!
        assertEquals(60.0, lap.activeSec, 1e-9)
        assertEquals(120_000L, lap.endMs - lap.startMs)
    }

    @Test fun avgHrIgnoresZeros() {
        val t = started()
        listOf(0, 140, 0, 160).forEachIndexed { i, hr ->
            t.sample((i + 1) * 1000L, (i + 1).toDouble(), (i + 1).toDouble(), hr, 0.0)
        }
        assertEquals(150, t.mark(4_000, 4.0, 4.0)!!.avgHr)

        val none = started()
        for (s in 1..4) none.sample(s * 1000L, s.toDouble(), s.toDouble(), 0, 0.0)
        assertEquals(0, none.mark(4_000, 4.0, 4.0)!!.avgHr)
    }

    @Test fun ascentAndDescentSeparate() {
        val t = started()
        listOf(0.0, 5.0, 3.0, 8.0).forEachIndexed { i, alt ->
            t.sample((i + 1) * 1000L, (i + 1).toDouble(), (i + 1) * 3.0, 0, alt)
        }
        val lap = t.mark(4_000, 4.0, 12.0)!!
        assertEquals(10.0, lap.ascentM, 1e-9)
        assertEquals(2.0, lap.descentM, 1e-9)
    }

    @Test fun odometerResetNeverGoesNegative() {
        val t = started()
        listOf(100.0, 200.0, 5.0, 55.0).forEachIndexed { i, d ->
            t.sample((i + 1) * 1000L, (i + 1) * 10.0, d, 0, 0.0)
            assertTrue(t.live((i + 1) * 10.0, d)!!.distanceM >= 0.0)
        }
        val lap = t.mark(40_000, 40.0, 55.0)!!
        assertEquals(250.0, lap.distanceM, 1e-9)
    }

    @Test fun finishClosesLastLap() {
        val t = started()
        t.mark(60_000, 60.0, 250.0)
        val last = t.finish(90_000, 90.0, 370.0)!!
        assertEquals(2, last.index)
        assertEquals(120.0, last.distanceM, 1e-9)
        assertEquals(2, t.closed().size)
        assertNull(t.finish(95_000, 95.0, 380.0))
    }
}
