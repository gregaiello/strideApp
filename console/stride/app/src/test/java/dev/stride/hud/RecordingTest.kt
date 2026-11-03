package dev.stride.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

class RecordingTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sample(t: Long, d: Double = t / 400.0, extra: Map<String, Double> = emptyMap()) =
        Sample(t, t / 1000.0, d, 9.0, 2.5, 1.25, 140, true, extra)

    @Test fun roundTrip() {
        val dir = tmp.newFolder()
        val r = Recording.begin(dir, 1_700_000_000_000, "Greg")
        val samples = listOf(sample(1000), sample(2000, extra = mapOf("vo2" to 31.5)), sample(3000))
        samples.forEach(r::sample)
        val lap = Lap(1, 0, 3000, 3.0, 7.5, 140, 0.5, 0.0)
        r.lap(lap)
        r.extra(Extra(2500, "vo2", 30.25))
        r.finish("done", 1_762_000_000_000, calories = 123.0)

        val back = Recording.read(r.file)
        assertEquals(Recording.VERSION, back.version)
        assertEquals(1_700_000_000_000, back.startWallMs)
        assertEquals("Greg", back.walker)
        assertEquals(samples, back.samples)
        assertEquals(listOf(lap), back.laps)
        assertEquals(listOf(Extra(2500, "vo2", 30.25)), back.extras)
        assertEquals("done", back.status)
        assertEquals(1_762_000_000_000, back.correctedStartWallMs)
        assertEquals(123.0, back.calories, 1e-9)
    }

    @Test fun writesDotDecimalsUnderGermanLocale() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val dir = tmp.newFolder()
            val r = Recording.begin(dir, 1, "Greg")
            r.sample(sample(1000, d = 12.5))
            r.finish("done", 1)
            val text = r.file.readText()
            assertTrue(text.contains("12.5"))
            assertFalse(text.contains("12,5"))
            assertEquals(12.5, Recording.read(r.file).samples.single().distanceM, 0.0)
        } finally {
            Locale.setDefault(was)
        }
    }

    @Test fun orphanIsClosedAsInterrupted() {
        val dir = tmp.newFolder()
        val file = Recording.begin(dir, 5, "Greg").apply {
            sample(sample(1000)); sample(sample(2000))
        }.file   // never finished: the app was killed

        assertEquals(listOf(file), Recording.closeOrphans(dir))
        val back = Recording.read(file)
        assertEquals("interrupted", back.status)
        assertEquals(2, back.samples.size)
        assertTrue(Recording.closeOrphans(dir).isEmpty())
    }

    @Test fun truncatedLastLineIsSkipped() {
        val dir = tmp.newFolder()
        val r = Recording.begin(dir, 5, "Greg")
        r.sample(sample(1000)); r.sample(sample(2000))
        r.finish("done", 5)
        r.file.appendText("{\"t\":\"s\",\"tMs\":")
        assertEquals(2, Recording.read(r.file).samples.size)
    }

    @Test fun endCarriesCorrectedStart() {
        val dir = tmp.newFolder()
        val r = Recording.begin(dir, 1_262_304_000_000, "Greg")  // a console that thinks it is 2010
        r.finish("done", 0)
        val back = Recording.read(r.file)
        assertEquals(0L, back.correctedStartWallMs)
        assertEquals(1_262_304_000_000, back.startWallMs)
    }

    @Test fun discardDeletesFile() {
        val dir = tmp.newFolder()
        val r = Recording.begin(dir, 5, "Greg")
        r.sample(sample(1000))
        r.discard()
        assertFalse(r.file.exists())
    }

    @Test fun walkerNameIsFileSafe() {
        val dir = tmp.newFolder()
        val r = Recording.begin(dir, 1, "Ann Marie/../x")
        assertTrue(r.file.name, Regex("^1_[A-Za-z0-9_-]+\\.jsonl$").matches(r.file.name))
        assertEquals(dir.canonicalFile, r.file.canonicalFile.parentFile)
        r.finish("done", 1)
    }
}
