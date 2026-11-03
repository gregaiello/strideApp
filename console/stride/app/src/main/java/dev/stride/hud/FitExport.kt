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

    /** @return the .fit written, or null if the walk is too short to be worth one. */
    fun export(recording: File, outDir: File, identity: FitIdentity,
               zone: TimeZone = TimeZone.getDefault()): File? {
        val rec = Recording.read(recording)
        val last = rec.samples.lastOrNull() ?: return null
        // The same bar History uses, so a walk that is not in the log has no file either.
        if (last.distanceM < History.MIN_DISTANCE_M || last.activeSec < History.MIN_ELAPSED_S) return null

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
        return out
    }
}
