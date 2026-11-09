package dev.stride.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class FitExportTest {

    @get:Rule val tmp = TemporaryFolder()

    private val rome = TimeZone.getTimeZone("Europe/Rome")
    /** 2026-11-03T07:12 in Rome (UTC+1 in November). */
    private val start2026 = 1_793_686_320_000L
    /** 2010-01-01T00:00Z, what the board's clock says before NTP lands. */
    private val start2010 = 1_262_304_000_000L

    private fun recording(dir: File, rawStart: Long, fixedStart: Long, who: String,
                          seconds: Int = 300, mps: Double = 2.5): File {
        val r = Recording.begin(dir, rawStart, who)
        for (s in 0 until seconds) r.sample(Sample(s * 1000L, s.toDouble(), s * mps, mps * 3.6, 0.0, 0.0, 140, true))
        r.finish("done", fixedStart, 50.0)
        return r.file
    }

    @Test fun nameIsSafeAndUnique() {
        assertEquals("2026-11-03_0712_Ann_Marie_x.fit", FitExport.fileName(start2026, "Ann Marie/x", rome))
        val recs = tmp.newFolder(); val out = tmp.newFolder()
        val a = FitExport.export(recording(recs, start2026, start2026, "Ann Marie/x"), out, FitIdentity.GENERIC, rome)!!
        val b = FitExport.export(recording(recs, start2026, start2026, "Ann Marie/x"), out, FitIdentity.GENERIC, rome)!!
        assertEquals("2026-11-03_0712_Ann_Marie_x.fit", a.name)
        assertEquals("2026-11-03_0712_Ann_Marie_x_2.fit", b.name)
    }

    @Test fun usesCorrectedStart() {
        val recs = tmp.newFolder(); val out = tmp.newFolder()
        val fixed = FitExport.export(recording(recs, start2010, start2026, "Ann"), out, FitIdentity.GENERIC, rome)!!
        assertTrue(fixed.name, fixed.name.startsWith("2026-11-03_"))
        val unknown = FitExport.export(recording(recs, start2010, 0, "Ann"), out, FitIdentity.GENERIC, rome)!!
        assertTrue(unknown.name, unknown.name.startsWith("2010-01-01_"))
    }

    @Test fun tooShortIsNotExported() {
        val recs = tmp.newFolder(); val out = tmp.newFolder()
        assertNull(FitExport.export(recording(recs, start2026, start2026, "Ann", seconds = 120, mps = 2.1),
            out, FitIdentity.GENERIC, rome))
        assertNull(FitExport.export(recording(recs, start2026, start2026, "Ann", seconds = 600, mps = 0.4),
            out, FitIdentity.GENERIC, rome))
        assertEquals(0, out.listFiles()!!.size)
    }

    /** A walk whose export never finished is tried again at the next start. */
    @Test fun exportedRecordingLeavesTheBacklog() {
        val recs = tmp.newFolder(); val out = tmp.newFolder()
        val r = recording(recs, start2026, start2026, "Ann")
        assertEquals(listOf(r), FitExport.pending(recs))
        FitExport.export(r, out, FitIdentity.GENERIC, rome)
        assertEquals(emptyList<File>(), FitExport.pending(recs))
    }

    @Test fun tooShortLeavesTheBacklogToo() {
        val recs = tmp.newFolder(); val out = tmp.newFolder()
        val r = recording(recs, start2026, start2026, "Ann", seconds = 60)
        assertNull(FitExport.export(r, out, FitIdentity.GENERIC, rome))
        assertEquals(emptyList<File>(), FitExport.pending(recs))
    }

    @Test fun failedExportStaysInTheBacklog() {
        val recs = tmp.newFolder()
        val r = recording(recs, start2026, start2026, "Ann")
        val notADir = tmp.newFile("blocked")
        try { FitExport.export(r, notADir, FitIdentity.GENERIC, rome) } catch (_: Exception) {}
        assertEquals(listOf(r), FitExport.pending(recs))
    }

    @Test fun staleTmpIsSwept() {
        val out = tmp.newFolder()
        File(out, "2026-11-03_0712_Ann.fit.tmp").writeText("half")
        File(out, "2026-11-03_0712_Ann.fit").writeText("kept")
        FitExport.sweepPartial(out)
        assertEquals(listOf("2026-11-03_0712_Ann.fit"), out.list()!!.toList())
    }

    @Test fun writesDecodableFile() {
        val recs = tmp.newFolder(); val out = tmp.newFolder()
        val f = FitExport.export(recording(recs, start2026, start2026, "Ann"), out, FitIdentity.GENERIC, rome)!!
        val bytes = f.readBytes()
        assertEquals(14, bytes[0].toInt())
        assertEquals(".FIT", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(0, FitWriter.crc(bytes))
        // No .tmp left behind; .order is the ring's record of write order.
        assertEquals(listOf(".order", f.name), out.list()!!.sorted())
    }

    /** One minute apart, so every export gets its own name. */
    private fun walks(recs: File, out: File, n: Int, from: Long = start2026): List<File> =
        (0 until n).map { i ->
            FitExport.export(recording(recs, from + i * 60_000L, from + i * 60_000L, "Ann"),
                out, FitIdentity.GENERIC, rome)!!
        }

    @Test fun ringKeepsTheLastTen() {
        val recs = tmp.newFolder(); val out = tmp.newFolder()
        val made = walks(recs, out, 13)
        val gone = FitExport.prune(out, recs, emptySet())
        assertEquals(made.take(3).map { it.name }, gone.sorted())
        assertEquals(made.drop(3).reversed(), FitExport.newestFirst(out))
        assertEquals(FitExport.KEEP, Recording.list(recs).size)
    }

    /** Today's walk named 2010 by a clock NTP has not reached is still the newest. */
    @Test fun ringGoesByWriteOrderNotByName() {
        val recs = tmp.newFolder(); val out = tmp.newFolder()
        walks(recs, out, 10)
        val late = walks(recs, out, 1, from = start2010).single()
        assertTrue(late.name, late.name.startsWith("2010-"))
        FitExport.prune(out, recs, emptySet())
        assertEquals(late, FitExport.newestFirst(out).first())
        assertTrue(late.exists())
    }

    /** A NAS asleep for a fortnight must not cost the fortnight's walks. */
    @Test fun ringKeepsWalksStillWaitingForTheNas() {
        val recs = tmp.newFolder(); val out = tmp.newFolder()
        val made = walks(recs, out, 12)
        FitExport.prune(out, recs, setOf(made[0].name))
        assertTrue(made[0].exists())
        assertTrue(!made[1].exists())
        assertEquals(FitExport.KEEP + 1, FitExport.newestFirst(out).size)
    }

    /** A recording not yet made into a .fit is a walk not yet saved. */
    @Test fun ringKeepsUnexportedRecordings() {
        val recs = tmp.newFolder(); val out = tmp.newFolder()
        walks(recs, out, 11)
        val raw = recording(recs, start2026 - 3_600_000L, start2026 - 3_600_000L, "Ann")
        FitExport.prune(out, recs, emptySet())
        assertTrue(raw.exists())
        assertEquals(listOf(raw), FitExport.pending(recs))
    }
}
