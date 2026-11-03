package dev.stride.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

class FitActivityTest {

    private val start = 1_762_153_920_000L   // 2025-11-03T07:12:00Z
    private val utc = TimeZone.getTimeZone("UTC")

    /**
     * One sample a second. [moving] decides per second; active time and
     * distance only advance while moving, as on the console.
     */
    private fun walk(
        seconds: Int,
        mps: Double = 2.5,
        hr: (Int) -> Int = { 140 },
        alt: (Int) -> Double = { 0.0 },
        moving: (Int) -> Boolean = { true },
    ): List<Sample> {
        var active = 0.0
        var dist = 0.0
        return (0 until seconds).map { s ->
            val mv = moving(s)
            if (mv && s > 0) { active += 1.0; dist += mps }
            Sample(s * 1000L, active, dist, if (mv) mps * 3.6 else 0.0, 0.0, alt(s), hr(s), mv)
        }
    }

    private fun lapOver(i: Int, samples: List<Sample>, from: Int, to: Int) = Lap(
        i, samples[from].tMs, samples[to].tMs,
        samples[to].activeSec - samples[from].activeSec,
        samples[to].distanceM - samples[from].distanceM, 140, 0.0, 0.0,
    )

    private fun rec(samples: List<Sample>, laps: List<Lap>, status: String = "done", calories: Double = 0.0) =
        Recorded(File("x.jsonl"), 1, start, "Ann", samples, laps, emptyList(), status, start, calories)

    private fun msgs(rec: Recorded, id: FitIdentity = FitIdentity.GENERIC) =
        FitTestReader.messages(FitActivity.encode(rec, id, utc))

    private fun s16(v: Long) = v.toInt().toShort().toLong()
    private fun s32(v: Long) = v.toInt().toLong()

    @Test fun structureInOrder() {
        val s = walk(300)
        val bytes = FitActivity.encode(rec(s, listOf(lapOver(1, s, 0, 150), lapOver(2, s, 150, 299))), FitIdentity.GENERIC, utc)
        assertEquals(0, FitWriter.crc(bytes))
        val globals = FitTestReader.messages(bytes).map { it.first }
        assertEquals(listOf(0, 23, 21) + List(300) { 20 } + listOf(21, 19, 19, 18, 34), globals)
    }

    @Test fun pausesBecomeTimerEvents() {
        val s = walk(150, moving = { it < 60 || it >= 90 })
        val m = msgs(rec(s, emptyList()))
        assertEquals(120, m.count { it.first == 20 })
        val events = m.filter { it.first == 21 }.map { it.second[1] }
        assertEquals(listOf(0L, 4L, 0L, 4L), events)
        val session = m.single { it.first == 18 }.second
        assertEquals(s.last().activeSec * 1000, session[8]!!.toDouble(), 0.5)
        assertEquals(149_000L, session[7])
        // The pause's stop lands at the first paused second, the restart at the first moving one.
        val times = m.filter { it.first == 21 }.map { it.second[253]!! - FitWriter.fitTime(start) }
        assertEquals(listOf(0L, 60L, 90L, 149L), times)
    }

    @Test fun noHrIsInvalidNotZero() {
        val m = msgs(rec(walk(200, hr = { 0 }), emptyList()))
        m.filter { it.first == 20 }.forEach { assertEquals(0xFFL, it.second[3]) }
        assertEquals(0xFFL, m.single { it.first == 18 }.second[16])
        assertEquals(0xFFL, m.single { it.first == 19 }.second[15])
    }

    @Test fun altitudeBelowStartEncodes() {
        val m = msgs(rec(walk(200, alt = { -4.6 }), emptyList()))
        assertEquals(2477L, m.first { it.first == 20 }.second[2])
    }

    @Test fun interruptedWalkStillHasLapAndSession() {
        val s = walk(200)
        val m = msgs(rec(s, listOf(lapOver(1, s, 0, 60)), status = "interrupted"))
        val laps = m.filter { it.first == 19 }.map { it.second }
        assertEquals(2, laps.size)
        assertEquals(0L, laps[0][24])
        assertEquals(7L, laps[1][24])
        assertEquals((s.last().distanceM - s[60].distanceM) * 100, laps[1][9]!!.toDouble(), 1.0)
        val session = m.single { it.first == 18 }.second
        assertEquals(2L, session[26])
    }

    @Test fun identityIsWritten() {
        val s = walk(200)
        val baro = msgs(rec(s, emptyList()), FitIdentity(1, 3906)).first { it.first == 0 }.second
        assertEquals(1L, baro[1]); assertEquals(3906L, baro[2])
        val plain = msgs(rec(s, emptyList())).first { it.first == 0 }.second
        assertEquals(255L, plain[1]); assertEquals(0L, plain[2])
    }

    @Test fun positionsFollowTheCircle() {
        val m = msgs(rec(walk(200), emptyList()))
        val at100 = m.first { it.first == 20 && it.second[5] == 10_000L }.second
        val (lat, lon) = SyntheticTrack.at(100.0)
        assertEquals(FitWriter.semicircles(lat), s32(at100[0]!!))
        assertEquals(FitWriter.semicircles(lon), s32(at100[1]!!))
    }

    @Test fun sessionTotals() {
        val s = walk(300, hr = { if (it < 150) 120 else 160 }, alt = { if (it < 100) it * 0.1 else 10.0 - (it - 100) * 0.02 })
        val session = msgs(rec(s, emptyList(), calories = 87.4)).single { it.first == 18 }.second
        assertEquals(87L, session[11])
        assertEquals(2500L, session[14])
        assertEquals(160L, session[17])
        assertEquals(10L, session[22])
        assertEquals(4L, session[23])
        assertEquals(s.last().distanceM * 100, session[9]!!.toDouble(), 0.5)
    }

    @Test fun localTimestampCarriesTheZone() {
        val rome = TimeZone.getTimeZone("Europe/Rome")
        val m = FitTestReader.messages(FitActivity.encode(rec(walk(200), emptyList()), FitIdentity.GENERIC, rome))
        val act = m.single { it.first == 34 }.second
        assertEquals(3600L, act[5]!! - act[253]!!)
    }

    @Test fun unknownDateUsesRawStart() {
        val r = rec(walk(200), emptyList()).copy(correctedStartWallMs = 0, startWallMs = 1_262_304_000_000)
        val fid = FitTestReader.messages(FitActivity.encode(r, FitIdentity.GENERIC, utc)).first { it.first == 0 }.second
        assertEquals(FitWriter.fitTime(1_262_304_000_000), fid[4])
    }

    /** Writes a file the Python decoder (fit-tool) can check independently. */
    @Test fun writesSampleForInspection() {
        val s = walk(1200, hr = { 130 + it / 30 }, alt = { it * 0.05 }, moving = { it < 500 || it >= 560 })
        val i1 = 400; val i2 = 800
        val laps = listOf(lapOver(1, s, 0, i1), lapOver(2, s, i1, i2), lapOver(3, s, i2, 1199))
        val out = File("build/fit-sample").apply { mkdirs() }
        File(out, "sample.fit").writeBytes(FitActivity.encode(rec(s, laps, calories = 150.0), FitIdentity.GENERIC, utc))
        File(out, "sample.expect").writeText(
            "records=${s.count { it.moving }} laps=3 distance=${s.last().distanceM} timer=${s.last().activeSec}\n")
        assertTrue(File(out, "sample.fit").length() > 1000)
    }
}
