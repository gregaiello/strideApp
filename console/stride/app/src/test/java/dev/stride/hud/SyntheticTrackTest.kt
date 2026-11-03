package dev.stride.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticTrackTest {

    private fun haversine(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6371008.8
        val p1 = Math.toRadians(a.first); val p2 = Math.toRadians(b.first)
        val dp = p2 - p1; val dl = Math.toRadians(b.second - a.second)
        val h = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    @Test fun startsNorthOfCentre() {
        val p = SyntheticTrack.at(0.0)
        assertTrue(p.first > SyntheticTrack.CENTRE_LAT)
        assertEquals(SyntheticTrack.CENTRE_LON, p.second, 1e-9)
    }

    @Test fun closesTheLoop() {
        val a = SyntheticTrack.at(0.0); val b = SyntheticTrack.at(400.0)
        assertEquals(a.first, b.first, 1e-9)
        assertEquals(a.second, b.second, 1e-9)
    }

    @Test fun mapDistanceMatchesBelt() {
        var sum = 0.0
        for (i in 0 until 400) sum += haversine(SyntheticTrack.at(i.toDouble()), SyntheticTrack.at(i + 1.0))
        assertEquals(400.0, sum, 1.0)
    }
}
