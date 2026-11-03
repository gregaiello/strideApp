package dev.stride.hud

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * One second of a workout.
 *
 * [tMs] is the monotonic offset from the session's start (see [Lap]); the
 * wall-clock date is applied once, at export, from the corrected start.
 * [altitudeM] is net — a descent lowers it — so ascent and descent are derived
 * from it rather than stored.
 *
 * [extra] is for sensors that do not exist yet. A metabolic cart, say, adds
 * "vo2" or "rer" here, and nothing that reads or writes a recording has to
 * change for it.
 */
data class Sample(
    val tMs: Long,
    val activeSec: Double,
    val distanceM: Double,
    val speedKph: Double,
    val inclinePct: Double,
    val altitudeM: Double,
    val hr: Int,
    val moving: Boolean,
    val extra: Map<String, Double> = emptyMap(),
)

/**
 * A reading that does not fall on the one-second grid — a breath, say, from a
 * breath-by-breath analyser — kept at its own timestamp rather than smeared
 * into the nearest second.
 */
data class Extra(val tMs: Long, val channel: String, val value: Double)

/** A recording as read back from disk. */
data class Recorded(
    val file: File,
    val version: Int,
    /** The wall clock at the start, as the console believed it at the time. */
    val startWallMs: Long,
    val walker: String,
    val samples: List<Sample>,
    val laps: List<Lap>,
    val extras: List<Extra>,
    /** "done", "interrupted", or null while still being written. */
    val status: String?,
    /** The start, corrected after the fact; 0 means the date is unknowable. */
    val correctedStartWallMs: Long,
)

/**
 * Every second of a workout, written to the console as it happens.
 *
 * **Why on the console.** The file this becomes is for Strava and Coros, and it
 * has to exist with no broker, no network and no Home Assistant, the same rule
 * the rest of the console follows. History.kt keeps a walk's totals; this keeps
 * the walk.
 *
 * **Why JSON lines.** A workout is appended to a line at a time and flushed as
 * it goes, so a console that loses power, or an app that Android kills while a
 * film is playing, leaves everything up to the last second on disk. A file
 * with no end line is an interrupted walk, not a corrupt one: [closeOrphans]
 * marks it so on the next start and [read] skips a half-written last line.
 * One JSON document per workout would be unreadable the moment it was cut off.
 *
 * Numbers go through org.json, which writes them the same way in every locale.
 * The console's own formatting does not: `"%.1f".format` writes 12,5 on a
 * console set to German or Italian.
 */
class Recording private constructor(val file: File) {

    companion object {
        const val VERSION = 1

        fun begin(dir: File, startWallMs: Long, walker: String): Recording {
            dir.mkdirs()
            val safe = walker.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "_" }
            var file = File(dir, "${startWallMs}_$safe.jsonl")
            var n = 1
            // Two walks inside a second, or a clock that has gone backwards.
            while (file.exists()) file = File(dir, "${startWallMs}_${safe}_${n++}.jsonl")
            return Recording(file).also {
                it.line(JSONObject().put("t", "h").put("v", VERSION)
                    .put("start", startWallMs).put("who", walker))
            }
        }

        fun read(file: File): Recorded {
            var version = 0
            var start = 0L
            var who = ""
            var status: String? = null
            var fixed = 0L
            val samples = mutableListOf<Sample>()
            val laps = mutableListOf<Lap>()
            val extras = mutableListOf<Extra>()
            file.forEachLine { text ->
                val o = try { JSONObject(text) } catch (_: JSONException) { return@forEachLine }
                try {
                    when (o.optString("t")) {
                        "h" -> { version = o.getInt("v"); start = o.getLong("start"); who = o.getString("who") }
                        "s" -> samples.add(sampleOf(o))
                        "l" -> laps.add(lapOf(o))
                        "x" -> extras.add(Extra(o.getLong("tMs"), o.getString("ch"), o.getDouble("val")))
                        "e" -> { status = o.getString("status"); fixed = o.getLong("startFixed") }
                    }
                } catch (_: JSONException) {
                    // A line that parsed but lacks a field is as good as a
                    // truncated one. Skipping it keeps the rest of the walk.
                }
            }
            return Recorded(file, version, start, who, samples, laps, extras, status, fixed)
        }

        /**
         * Close every recording that never got an end line: the app died while
         * it was being written. Returns the files it closed.
         *
         * Call it once at start-up, before any workout can begin. It cannot
         * tell a dead walk from a live one, and does not try: at start-up
         * there is no live one.
         */
        fun closeOrphans(dir: File): List<File> = list(dir).filter { f ->
            if (lastLineIsEnd(f)) return@filter false
            appendLine(f, JSONObject().put("t", "e").put("status", "interrupted").put("startFixed", 0L))
            true
        }

        /** Only the tail is read, so a sweep over months of walks stays instant. */
        private fun lastLineIsEnd(f: File): Boolean = RandomAccessFile(f, "r").use { raf ->
            val n = minOf(raf.length(), 4096L).toInt()
            val buf = ByteArray(n)
            raf.seek(raf.length() - n)
            raf.readFully(buf)
            val last = String(buf, Charsets.UTF_8).trimEnd('\n').substringAfterLast('\n')
            try { JSONObject(last).optString("t") == "e" } catch (_: JSONException) { false }
        }

        /** Newest first. */
        fun list(dir: File): List<File> =
            (dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: emptyArray())
                .sortedByDescending { it.name.substringBefore('_').toLongOrNull() ?: 0L }

        private fun appendLine(file: File, o: JSONObject) {
            // A kill can leave a half-written last line. Starting the new one
            // on a fresh line keeps it from being glued onto the fragment.
            val needsBreak = file.length() > 0 && RandomAccessFile(file, "r").use {
                it.seek(file.length() - 1); it.read() != '\n'.code
            }
            FileOutputStream(file, true).use { out ->
                if (needsBreak) out.write('\n'.code)
                out.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
        }

        private fun sampleOf(o: JSONObject): Sample {
            val x = o.optJSONObject("x")
            val extra = if (x == null) emptyMap() else
                x.keys().asSequence().associateWith { x.getDouble(it) }
            return Sample(
                tMs = o.getLong("tMs"), activeSec = o.getDouble("a"), distanceM = o.getDouble("d"),
                speedKph = o.getDouble("v"), inclinePct = o.getDouble("i"), altitudeM = o.getDouble("alt"),
                hr = o.getInt("hr"), moving = o.getBoolean("mv"), extra = extra,
            )
        }

        private fun lapOf(o: JSONObject) = Lap(
            index = o.getInt("n"), startMs = o.getLong("s"), endMs = o.getLong("e"),
            activeSec = o.getDouble("a"), distanceM = o.getDouble("d"), avgHr = o.getInt("hr"),
            ascentM = o.getDouble("up"), descentM = o.getDouble("down"),
        )
    }

    private var out: FileOutputStream? = FileOutputStream(file, true)

    @Synchronized fun sample(s: Sample) {
        val o = JSONObject().put("t", "s").put("tMs", s.tMs).put("a", s.activeSec)
            .put("d", s.distanceM).put("v", s.speedKph).put("i", s.inclinePct)
            .put("alt", s.altitudeM).put("hr", s.hr).put("mv", s.moving)
        if (s.extra.isNotEmpty()) {
            o.put("x", JSONObject().apply { s.extra.forEach { (k, v) -> put(k, v) } })
        }
        line(o)
    }

    @Synchronized fun lap(l: Lap) = line(
        JSONObject().put("t", "l").put("n", l.index).put("s", l.startMs).put("e", l.endMs)
            .put("a", l.activeSec).put("d", l.distanceM).put("hr", l.avgHr)
            .put("up", l.ascentM).put("down", l.descentM)
    )

    @Synchronized fun extra(e: Extra) =
        line(JSONObject().put("t", "x").put("tMs", e.tMs).put("ch", e.channel).put("val", e.value))

    /** @param correctedStartWallMs the start once the clock is believed; 0 = unknown. */
    @Synchronized fun finish(status: String, correctedStartWallMs: Long) {
        line(JSONObject().put("t", "e").put("status", status).put("startFixed", correctedStartWallMs))
        close()
    }

    /** Too short, or nobody's: a walk not worth a file is not left as one. */
    @Synchronized fun discard() {
        close()
        file.delete()
    }

    private fun line(o: JSONObject) {
        val stream = out ?: return
        // Flushed, not synced: once a second is gentle on the console's
        // flash, and the kernel writes it out within seconds anyway. What
        // this guards against is the app being killed, which a flush covers.
        stream.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
        stream.flush()
    }

    private fun close() {
        try { out?.close() } catch (_: Exception) {}
        out = null
    }
}
