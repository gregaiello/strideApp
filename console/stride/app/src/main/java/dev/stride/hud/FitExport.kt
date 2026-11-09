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
        remember(outDir, out.name)
        // Last, so a kill before here means a retry. The worst a retry can
        // do is a duplicate with a `_2`, never a missing walk.
        marker(recording).createNewFile()
        return out
    }

    /**
     * How many walks the console keeps, as .fit files and as the recordings
     * they were made from. Past this the oldest go, so the console is a ring
     * of the last ten rather than a store that grows until the disk is full
     * (2026-11-09, at the user's request: the NAS is where walks are kept).
     */
    const val KEEP = 10

    /**
     * The order the files were written in, one name per line, oldest first.
     *
     * Kept because neither the names nor the file times can say which walk
     * is newest on this console. Both come off the wall clock, which reads
     * 2010 until NTP lands and sometimes all walk — so a walk recorded before
     * the clock was right is named `2010-01-01_…`, sorts as the oldest file
     * there is, and would be the first one a ring by name threw away: the
     * walk just finished, still on the summary screen with its QR code.
     */
    private fun orderFile(outDir: File) = File(outDir, ".order")

    @Synchronized
    private fun remember(outDir: File, name: String) {
        val f = orderFile(outDir)
        val names = (if (f.exists()) f.readLines() else emptyList()).filter { it.isNotBlank() && it != name }
        // Rewritten whole and short, rather than appended for ever.
        val kept = (names + name).takeLast(KEEP * 4)
        val part = File(outDir, ".order.tmp")
        part.writeText(kept.joinToString("\n", postfix = "\n"))
        if (!part.renameTo(f)) { f.delete(); part.renameTo(f) }
    }

    /**
     * Every finished .fit, newest first. A file the order does not know (one
     * written before it existed) counts as older than every file it does
     * know, and those sort among themselves by name.
     */
    @Synchronized
    fun newestFirst(outDir: File): List<File> {
        val files = outDir.listFiles { f -> f.isFile && f.name.endsWith(".fit") } ?: return emptyList()
        val f = orderFile(outDir)
        val rank = (if (f.exists()) f.readLines() else emptyList())
            .withIndex().associate { (i, n) -> n to i }
        return files.sortedWith(compareByDescending<File> { rank[it.name] ?: -1 }
            .thenByDescending { it.name })
    }

    /**
     * Drops what is past the last [keep] walks.
     *
     * @param waiting names of .fit files still queued for the NAS. They stay
     *   whatever their age: a NAS asleep for a fortnight must not mean the
     *   fortnight's walks are gone before it wakes.
     *
     * Recordings go by the same count, but only ones already settled into a
     * .fit (or judged too short): an unsettled one is a walk not yet exported,
     * and deleting it would lose the walk.
     *
     * @return the names of the .fit files deleted.
     */
    @Synchronized
    fun prune(outDir: File, recordingsDir: File, waiting: Set<String>, keep: Int = KEEP): List<String> {
        val gone = newestFirst(outDir).drop(keep)
            .filter { it.name !in waiting }
            .filter { it.delete() }
            .map { it.name }
        Recording.list(recordingsDir).filter { marker(it).exists() }.drop(keep).forEach {
            if (it.delete()) marker(it).delete()
        }
        return gone
    }
}
