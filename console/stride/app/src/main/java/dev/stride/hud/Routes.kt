package dev.stride.hud

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * Routes converted from real outdoor walks, cached on the console.
 *
 * They come from GPX files in the NAS's routes/ folder (NasCopy.fetchRoutes)
 * and are **cached to disk on arrival**: the console can boot with no network
 * at all, and a route fetched at workout time would fail at the worst possible
 * moment — standing on the belt, having chosen a walk.
 *
 * They used to arrive over MQTT from Home Assistant too, from a phone. This
 * fork has no Home Assistant or MQTT (2026-11-09), so nothing new comes that
 * way, but whatever the phone had already sent is still in `routes.json` and
 * is still offered: a route somebody walks should not vanish because the
 * road it came by was closed.
 *
 * There is no GPS here and never will be. What arrives is a gradient profile:
 * `[startM, endM, incline]` triples against distance travelled.
 */
class Routes(context: Context) {

    private val file = File(context.filesDir, "routes.json")

    @Volatile
    private var raw: String = "[]"

    init {
        raw = try {
            if (file.exists()) file.readText() else "[]"
        } catch (e: Exception) {
            Log.w(TAG, "cache unreadable (${e.message})")
            "[]"
        }
        // Not count(): that includes the NAS list, declared below and so not
        // initialised yet when this block runs.
        Log.i(TAG, "loaded ${try { JSONArray(raw).length() } catch (e: Exception) { 0 }} route(s) from disk")
    }

    /*
     * Fork-only: routes converted from GPX files in the NAS's routes/ folder
     * (NasCopy.fetchRoutes). Kept in their own file, apart from the phone's
     * old ones in [raw], so a NAS fetch never wipes those.
     */
    private val nasFile = File(context.filesDir, "routes_nas.json")

    @Volatile
    private var nasRaw: String = try {
        if (nasFile.exists()) nasFile.readText() else "[]"
    } catch (e: Exception) { "[]" }

    /** The NAS's set, replacing the last one. */
    fun acceptNas(routes: JSONArray) {
        nasRaw = routes.toString()
        try {
            nasFile.writeText(nasRaw)
        } catch (e: Exception) {
            Log.w(TAG, "cannot write NAS route cache (${e.message})")
        }
        Log.i(TAG, "cached ${routes.length()} route(s) from the NAS")
    }

    /** The array as it will be handed to the UI: the phone's, then the NAS's. */
    fun json(): String {
        val nas = try { JSONArray(nasRaw) } catch (e: Exception) { JSONArray() }
        if (nas.length() == 0) return raw
        val all = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        for (i in 0 until nas.length()) all.put(nas.get(i))
        return all.toString()
    }

    fun count(): Int = try { JSONArray(json()).length() } catch (e: Exception) { 0 }

    /**
     * One route by id, or null.
     *
     * Kept here rather than in JavaScript because Kotlin has to resolve the
     * chosen route into plan steps anyway, and two parsers for one format is
     * one more than the format deserves.
     */
    fun byId(id: String): Route? {
        try {
            val arr = JSONArray(json())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("id") != id) continue
                val segs = o.optJSONArray("segments") ?: return null
                val out = ArrayList<Route.Segment>(segs.length())
                for (j in 0 until segs.length()) {
                    val t = segs.optJSONArray(j) ?: continue
                    if (t.length() < 3) continue
                    out.add(Route.Segment(t.getDouble(0), t.getDouble(1),
                                          t.getDouble(2)))
                }
                if (out.isEmpty()) return null
                return Route(id = id,
                             name = o.optString("name", "Route"),
                             distanceM = o.optDouble("distance_m", out.last().endM),
                             climbM = o.optDouble("climb_m", 0.0),
                             segments = out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read route $id (${e.message})")
        }
        return null
    }

    companion object { private const val TAG = "StrideRoutes" }
}

/**
 * A route as the deck will walk it.
 *
 * Segments are in **metres travelled**, not seconds elapsed, and that is the
 * substantive difference from `Plan.Template`. Replaying a route on a clock
 * while walking slower indoors than out means hitting the hill late and never
 * finishing the route; driving it by distance means the ground arrives where it
 * did outdoors however fast you take it.
 */
data class Route(
    val id: String,
    val name: String,
    val distanceM: Double,
    val climbM: Double,
    val segments: List<Segment>,
) {
    data class Segment(val startM: Double, val endM: Double, val incline: Double)

    /**
     * The same route walked out and then walked home.
     *
     * A recorded walk is usually one way — the 30 minutes from the hospital to
     * the front door is a fine walk and a poor workout, because it is over when
     * you have done it once. Turning round is what you would do outdoors, and
     * it is a different walk from going round twice: the hill you climbed on
     * the way out is the hill you come down on the way back, and the flat bit
     * you enjoyed at the start is the drag at the end.
     *
     * So the return leg is the outbound one **reversed and inverted**, not
     * repeated. At `distanceM + x` you are standing where you were at
     * `distanceM - x`, facing the other way, which is exactly what negating the
     * gradient means.
     *
     * Two honest limitations, both the machine's rather than this function's:
     *
     *  * **The deck stops at −3%.** Come back down anything steeper than that
     *    and the descent is clamped, by the same `coerceIn` every route goes
     *    through. A 9% climb on the way out is a 3% descent on the way home.
     *    The ascents are untouched, because nothing outdoors was steeper going
     *    down than the deck can climb going up.
     *  * **Climb is recomputed, not doubled.** Total ascent over an out-and-back
     *    is the outbound ascent plus the outbound *descent*, which for a route
     *    that does not end where it started is not twice anything. It comes out
     *    as the sum of the absolute rise of every segment.
     *
     * Per walk, never stored: the loop button on the picker chooses it for this
     * session and the cached route is untouched.
     */
    fun outAndBack(): Route {
        val total = distanceM
        val back = segments.asReversed().map {
            Segment(startM = total + (total - it.endM),
                    endM = total + (total - it.startM),
                    incline = -it.incline)
        }
        val ascent = segments.sumOf { Math.abs(it.incline) / 100.0 * (it.endM - it.startM) }
        return copy(name = "$name, there and back",
                    distanceM = total * 2,
                    climbM = ascent,
                    segments = segments + back)
    }

    /** The incline at a given distance into the walk. */
    fun inclineAt(metres: Double): Double {
        if (segments.isEmpty()) return 0.0
        for (s in segments) if (metres < s.endM) return s.incline
        return segments.last().incline
    }

    /** How far through, 0..1. Used for the progress the HUD already shows. */
    fun progressAt(metres: Double): Double =
        if (distanceM <= 0) 0.0 else (metres / distanceM).coerceIn(0.0, 1.0)
}
