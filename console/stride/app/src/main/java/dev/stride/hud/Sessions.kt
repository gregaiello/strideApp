package dev.stride.hud

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Structured sessions: intervals and progressions, saved by name.
 *
 * Fork-only (2026-11-21). These replace the four guided-walk templates, which
 * were never walked again once there was a reason to train rather than wander.
 * A session is the opposite of a template in one respect that matters: **it
 * drives the belt speed.** Somebody running 8 × 1 min is watching the clock and
 * their breathing, not the + button, and a plan that only suggested the "on"
 * pace would mean a tap at every rep. The rest of upstream's rule stands: every
 * target is clamped to what the board reports, speed only ever ramps, and a
 * press on the console outranks the plan (see MainActivity.sessionTick).
 *
 * Stored as entered, clamped when resolved. A session saved on a day the board
 * had not yet reported its range (and the fallbacks said 20 km/h) must not come
 * back rewritten to whatever it was clamped to that morning.
 */
class Sessions(context: Context) {

    private val file = File(context.filesDir, "sessions.json")
    private val lock = Any()

    /** One block of a session: how long, how steep, how fast. */
    data class Block(val sec: Double, val grade: Double, val kph: Double)

    data class Def(
        val id: String,
        val name: String,
        /** `interval` or `progression`. */
        val kind: String,
        val warmup: Block,
        val cooldown: Block,
        val on: Block? = null,
        val off: Block? = null,
        val reps: Int = 0,
        val progSec: Double = 0.0,
        val startGrade: Double = 0.0,
        val endGrade: Double = 0.0,
        val startKph: Double = 0.0,
        val endKph: Double = 0.0,
        /** `up`, `updown` or `down`. Start and end are literal either way. */
        val shape: String = "up",
    )

    companion object {
        private const val TAG = "Stride"
        const val MIN_SEC = 10.0
        const val MAX_SEC = 3 * 3600.0
        const val MAX_REPS = 50

        private fun block(o: JSONObject?): Block? {
            o ?: return null
            val b = Block(o.optDouble("sec", Double.NaN), o.optDouble("grade", Double.NaN),
                          o.optDouble("kph", Double.NaN))
            return if (okSec(b.sec) && b.grade.isFinite() && b.kph.isFinite()) b else null
        }

        private fun okSec(s: Double) = s.isFinite() && s >= MIN_SEC && s <= MAX_SEC

        /**
         * Read and validate one session, or null.
         *
         * Rejecting rather than repairing: a session with a NaN speed in it came
         * from a bug, and quietly turning it into zero would start a walk with
         * the belt at its minimum when the walker asked for something else.
         */
        fun parse(o: JSONObject): Def? {
            val warm = block(o.optJSONObject("warmup")) ?: return null
            val cool = block(o.optJSONObject("cooldown")) ?: return null
            val id = o.optString("id").ifEmpty { "s-" + System.currentTimeMillis() }
            val name = o.optString("name").trim().take(40)
            return when (o.optString("kind")) {
                "interval" -> {
                    val i = o.optJSONObject("interval") ?: return null
                    val on = block(i.optJSONObject("on")) ?: return null
                    val off = block(i.optJSONObject("off")) ?: return null
                    val reps = i.optInt("reps", 0)
                    if (reps < 1 || reps > MAX_REPS) return null
                    Def(id, name, "interval", warm, cool, on = on, off = off, reps = reps)
                }
                "progression" -> {
                    val p = o.optJSONObject("progression") ?: return null
                    val sec = p.optDouble("sec", Double.NaN)
                    val nums = listOf("startGrade", "endGrade", "startKph", "endKph")
                        .map { p.optDouble(it, Double.NaN) }
                    if (!okSec(sec) || nums.any { !it.isFinite() }) return null
                    val shape = p.optString("shape", "up")
                    if (shape !in listOf("up", "updown", "down")) return null
                    Def(id, name, "progression", warm, cool, progSec = sec,
                            startGrade = nums[0], endGrade = nums[1],
                            startKph = nums[2], endKph = nums[3], shape = shape)
                }
                else -> null
            }
        }

        private fun blockJson(b: Block) =
            JSONObject().put("sec", b.sec).put("grade", b.grade).put("kph", b.kph)

        fun toJson(s: Def): JSONObject {
            val o = JSONObject().put("id", s.id).put("name", s.name).put("kind", s.kind)
                .put("warmup", blockJson(s.warmup)).put("cooldown", blockJson(s.cooldown))
            if (s.kind == "interval") {
                o.put("interval", JSONObject().put("on", blockJson(s.on!!))
                    .put("off", blockJson(s.off!!)).put("reps", s.reps))
            } else {
                o.put("progression", JSONObject().put("sec", s.progSec)
                    .put("startGrade", s.startGrade).put("endGrade", s.endGrade)
                    .put("startKph", s.startKph).put("endKph", s.endKph)
                    .put("shape", s.shape))
            }
            return o
        }

        /**
         * A session laid out as plan steps with absolute targets.
         *
         * The off period after the last rep is dropped: the cool-down follows at
         * once, and an easy minute followed by an easy five is one easy six
         * that the screen would be counting down twice. This is how a sport
         * watch runs the same workout.
         */
        fun resolve(s: Def, minKph: Double, maxKph: Double,
                    minGrade: Double, maxGrade: Double): List<Plan.Step> {
            val steps = ArrayList<Plan.Step>()
            var at = 0.0
            fun add(sec: Double, label: String, grade: Double, kph: Double,
                    endGrade: Double = grade, endKph: Double = kph) {
                steps += Plan.Step(
                    startSec = at, endSec = at + sec,
                    incline = grade.coerceIn(minGrade, maxGrade),
                    paceDelta = 0.0, label = label, note = "",
                    kph = kph.coerceIn(minKph, maxKph),
                    endIncline = endGrade.coerceIn(minGrade, maxGrade),
                    endKph = endKph.coerceIn(minKph, maxKph),
                )
                at += sec
            }
            add(s.warmup.sec, "Warm-up", s.warmup.grade, s.warmup.kph)
            if (s.kind == "interval") {
                val on = s.on!!; val off = s.off!!
                for (r in 1..s.reps) {
                    add(on.sec, "Rep $r/${s.reps} · On", on.grade, on.kph)
                    if (r < s.reps) add(off.sec, "Rep $r/${s.reps} · Off", off.grade, off.kph)
                }
            } else if (s.shape == "updown") {
                val half = s.progSec / 2
                add(half, "Progression · up", s.startGrade, s.startKph, s.endGrade, s.endKph)
                add(half, "Progression · down", s.endGrade, s.endKph, s.startGrade, s.startKph)
            } else {
                add(s.progSec, "Progression", s.startGrade, s.startKph, s.endGrade, s.endKph)
            }
            add(s.cooldown.sec, "Cool-down", s.cooldown.grade, s.cooldown.kph)
            return steps
        }
    }

    fun all(): List<Def> = synchronized(lock) {
        try {
            val arr = JSONArray(if (file.exists()) file.readText() else "[]")
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::parse).also {
                    if (it == null) Log.w(TAG, "sessions: dropped an unreadable entry")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sessions: could not read ${file.name}", e)
            emptyList()
        }
    }

    fun json(): String = JSONArray().apply { all().forEach { put(toJson(it)) } }.toString()

    /** Insert or replace by id. Returns the id, or "" if it did not validate. */
    fun save(raw: String): String {
        val s = try { parse(JSONObject(raw)) } catch (e: Exception) { null }
            ?: return "".also { Log.w(TAG, "sessions: rejected a save") }
        synchronized(lock) {
            val list = all().filter { it.id != s.id } + s
            write(list)
        }
        Log.i(TAG, "sessions: saved \"${s.name}\" (${s.kind})")
        return s.id
    }

    fun delete(id: String) = synchronized(lock) {
        write(all().filter { it.id != id })
    }

    /** Temp file and rename, so a power cut mid-write leaves the old list. */
    private fun write(list: List<Def>) {
        val tmp = File(file.parentFile, "sessions.json.tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(toJson(it)) } }.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
