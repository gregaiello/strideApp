package dev.stride.hud

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Where a finished recording becomes a .fit file, and what it is called.
 *
 * Named by the walk's local start, as a watch names its files, so a folder of
 * them sorts by date and reads at a glance: `2026-11-03_0712_Ann.fit`.
 */
object FitExport {

    fun fileName(startWallMs: Long, walker: String, zone: TimeZone): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).apply { timeZone = zone }
        val safe = walker.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "_" }
        return "${fmt.format(Date(startWallMs))}_$safe.fit"
    }

    /**
     * Recordings whose export has not been settled: never tried, failed, or
     * cut off by a kill. A marker beside the recording says it was settled;
     * the .fit's own name cannot, since a second walk in the same minute
     * gets a `_2`.
     */
    fun pending(recordingsDir: File): List<File> =
        Recording.list(recordingsDir).filter { !marker(it).exists() }

    /** A `.tmp` is an export that never got to its rename. */
    fun sweepPartial(outDir: File) {
        outDir.listFiles { f -> f.name.endsWith(".fit.tmp") }?.forEach { it.delete() }
    }

    private fun marker(recording: File) = File(recording.path + ".exported")

    /**
     * @return the .fit written, or null if the walk is too short to be worth one.
     *
     * Synchronized because two exports choosing a name at once could both
     * find it free; the start-up backlog can hand over several together.
     */
    @Synchronized
    fun export(recording: File, outDir: File, identity: FitIdentity,
               zone: TimeZone = TimeZone.getDefault()): File? {
        val rec = Recording.read(recording)
        val last = rec.samples.lastOrNull()
        // The same bar History uses, so a walk that is not in the log has no file either.
        if (last == null || last.distanceM < History.MIN_DISTANCE_M || last.activeSec < History.MIN_ELAPSED_S) {
            marker(recording).createNewFile()
            return null
        }

        val start = if (rec.correctedStartWallMs > 0) rec.correctedStartWallMs else rec.startWallMs
        val name = fileName(start, rec.walker, zone)
        val stem = name.removeSuffix(".fit")
        outDir.mkdirs()
        var out = File(outDir, name)
        var n = 2
        while (out.exists()) out = File(outDir, "${stem}_${n++}.fit")

        // Written aside and renamed, so a kill mid-write never leaves a
        // half file that Strava would refuse, under a name that looks done.
        val part = File(outDir, out.name + ".tmp")
        part.writeBytes(FitActivity.encode(rec, identity, zone))
        if (!part.renameTo(out)) {
            part.delete()
            throw java.io.IOException("could not rename ${part.name}")
        }
        // Last, so a kill before here means a retry. The worst a retry can
        // do is a duplicate with a `_2`, never a missing walk.
        marker(recording).createNewFile()
        return out
    }
}
