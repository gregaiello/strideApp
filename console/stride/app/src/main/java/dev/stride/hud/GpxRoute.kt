package dev.stride.hud

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A GPX file turned into the gradient profile [Routes] walks.
 *
 * Fork-only, for routes dropped in the NAS's `routes/` folder (see
 * [NasCopy.fetchRoutes]). The output is the same `[startM, endM, incline]`
 * shape the phone sends, so everything downstream (the picker, out-and-back,
 * the board clamp) treats both alike. As with the phone, the track itself is
 * dropped: only distance and height survive.
 *
 * **Elevation is smoothed before it becomes incline.** GPS heights wander by
 * metres between points a few metres apart, and differenced raw they read as
 * 30 % ramps on a flat towpath, which the deck would dutifully chase. Heights
 * are resampled every [STEP_M] and averaged over [SMOOTH_M] each side, then
 * read off per [SEGMENT_M]. A barometric or map-corrected export (Strava,
 * Garmin, Komoot) barely changes; a phone's raw GPS heights become walkable.
 *
 * Inclines are rounded to half a percent and equal neighbours merged, so the
 * deck moves when the ground does and not for every 0.1 % of noise. The board's
 * own limits are applied later, when the route is walked, as for every route.
 *
 * Parsed by hand, not with an XML parser, so it runs in a plain JVM unit test
 * (android.jar's XmlPullParser is a stub there). GPX points are
 * `<trkpt lat lon>` or `<rtept lat lon>` with an optional `<ele>`, and that is
 * all this reads.
 *
 * **Tags are found with indexOf, and regexes only ever see one point.** The
 * first version matched `<trk>(.*?)</trk>` to find the name: harmless on the
 * JVM, but Android's ICU regex keeps a backtracking frame per character of a
 * lazy match, and across an 825 KB Strava export (2722 points with heart
 * rate, cadence and power) that ran the console out of memory. The low-memory
 * killer took STRIDE down with Netflix and everything else the instant FETCH
 * was pressed. GpxRouteTest cannot see this, because it is the JVM's engine,
 * not ICU's, that runs there; hence the rule rather than a test.
 */
object GpxRoute {

    const val STEP_M = 10.0
    const val SMOOTH_M = 100.0
    const val SEGMENT_M = 100.0
    /** Shorter than this is a mis-export or a walk to the car, not a route. */
    const val MIN_LENGTH_M = 200.0
    /** Steeper than any deck; a number past this is a broken height, not a hill. */
    const val MAX_GRADE = 40.0

    sealed class Result {
        data class Ok(val route: JSONObject) : Result()
        data class Skip(val why: String) : Result()
    }

    private val LAT = Regex("\\blat\\s*=\\s*[\"']([-+0-9.eE]+)[\"']")
    private val LON = Regex("\\blon\\s*=\\s*[\"']([-+0-9.eE]+)[\"']")
    private val ELE = Regex("<ele>\\s*([-+0-9.eE]+)\\s*</ele>")

    private class Pt(val lat: Double, val lon: Double, val ele: Double?)

    /** [fileName] names the route when the file does not, and makes its id. */
    fun convert(text: String, fileName: String): Result {
        val pts = points(text)
        if (pts.size < 2) return Result.Skip("no track in this file")
        val withEle = pts.filter { it.ele != null }
        // A file with heights on only a few points (some planners add one at
        // each waypoint) cannot be trusted for the ground in between.
        if (withEle.size < 2 || withEle.size < pts.size / 2)
            return Result.Skip("no elevation in this file")

        // Distance along the track, against height, for points that have one.
        val dist = ArrayList<Double>(withEle.size)
        val ele = ArrayList<Double>(withEle.size)
        var total = 0.0
        var prev: Pt? = null
        for (p in withEle) {
            if (prev != null) {
                val d = metres(prev, p)
                if (d < 0.5) continue          // a GPS standing still
                total += d
            }
            dist.add(total); ele.add(p.ele!!)
            prev = p
        }
        if (total < MIN_LENGTH_M) return Result.Skip("shorter than ${MIN_LENGTH_M.toInt()} m")

        val sampled = resample(dist, ele, total)
        val smooth = smooth(sampled)
        val climb = (1 until smooth.size).sumOf { i -> maxOf(0.0, smooth[i] - smooth[i - 1]) }

        val segs = JSONArray()
        var lastInc = Double.NaN
        var segStart = 0.0
        var at = 0.0
        while (at < total - 1e-6) {
            val end = min(at + SEGMENT_M, total)
            // A sliver at the end takes the grade of the stretch before it,
            // where its own few metres of height would be noise again.
            if (end - at < SEGMENT_M / 5 && segs.length() > 0) {
                (segs.get(segs.length() - 1) as JSONArray).put(1, round1(end))
                break
            }
            val rise = heightAt(smooth, end) - heightAt(smooth, at)
            val inc = (rise / (end - at) * 100.0).coerceIn(-MAX_GRADE, MAX_GRADE)
                .let { (it * 2).roundToInt() / 2.0 }
            if (inc == lastInc && segs.length() > 0) {
                (segs.get(segs.length() - 1) as JSONArray).put(1, round1(end))
            } else {
                segs.put(JSONArray().put(round1(segStart)).put(round1(end)).put(inc))
                lastInc = inc
            }
            at = end
            segStart = end
        }

        val name = nameOf(text) ?: fileName.substringBeforeLast('.')
        return Result.Ok(JSONObject()
            .put("id", "nas:$fileName")
            .put("name", name)
            .put("source", "nas")
            .put("distance_m", round1(total))
            .put("climb_m", round1(climb))
            .put("segments", segs))
    }

    /**
     * Every `<trkpt>` or `<rtept>`, in file order. Each point's body (up to its
     * closing tag) is a few hundred bytes at most, so the regexes run on that.
     */
    private fun points(text: String): List<Pt> {
        val out = ArrayList<Pt>()
        for (tag in arrayOf("trkpt", "rtept")) {
            var i = text.indexOf("<$tag", 0)
            while (i >= 0) {
                val after = i + tag.length + 1
                // "<trkptx" is some other tag; skip it.
                if (after < text.length && (text[after].isWhitespace() || text[after] == '>' || text[after] == '/')) {
                    val gt = text.indexOf('>', after)
                    if (gt < 0) break
                    val attrs = text.substring(after, gt)
                    val selfClosed = attrs.endsWith("/")
                    val bodyEnd = if (selfClosed) gt else text.indexOf("</$tag>", gt).let { if (it < 0) gt else it }
                    val body = if (selfClosed) "" else text.substring(gt + 1, bodyEnd)
                    val lat = LAT.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
                    val lon = LON.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
                    if (lat != null && lon != null)
                        out.add(Pt(lat, lon, ELE.find(body)?.groupValues?.get(1)?.toDoubleOrNull()))
                    i = text.indexOf("<$tag", bodyEnd)
                } else {
                    i = text.indexOf("<$tag", after)
                }
            }
            if (out.isNotEmpty()) break   // a file is a track or a route, not both
        }
        return out
    }

    /**
     * The track's own name: the first `<name>` after `<trk>` (or `<rte>`) and
     * before its first point. Failing that, the first `<name>` in the file,
     * which is the metadata's.
     */
    private fun nameOf(text: String): String? {
        fun nameAt(from: Int, before: Int): String? {
            val a = text.indexOf("<name>", from)
            if (a < 0 || a > before) return null
            val b = text.indexOf("</name>", a)
            if (b < 0) return null
            return text.substring(a + 6, b).trim().removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        }
        val trk = listOf(text.indexOf("<trk>"), text.indexOf("<trk "), text.indexOf("<rte>"), text.indexOf("<rte "))
            .filter { it >= 0 }.minOrNull()
        val firstPt = listOf(text.indexOf("<trkpt"), text.indexOf("<rtept")).filter { it >= 0 }.minOrNull() ?: text.length
        val name = (if (trk != null) nameAt(trk, firstPt) else null) ?: nameAt(0, firstPt)
        return name?.let(::unescape)?.takeIf { it.isNotEmpty() }?.take(60)
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Height every [STEP_M] metres, interpolated between the recorded points. */
    private fun resample(dist: List<Double>, ele: List<Double>, total: Double): DoubleArray {
        val n = (total / STEP_M).toInt() + 2
        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val d = min(i * STEP_M, total)
            while (j < dist.size - 2 && dist[j + 1] < d) j++
            val span = dist[j + 1] - dist[j]
            val t = if (span <= 0) 0.0 else ((d - dist[j]) / span).coerceIn(0.0, 1.0)
            out[i] = ele[j] + (ele[j + 1] - ele[j]) * t
        }
        return out
    }

    /**
     * A straight line fitted over the [SMOOTH_M] each side of every sample,
     * read at that sample. In the middle that is exactly a moving average. At
     * the ends the window slides inward instead of shrinking: a plain average
     * narrowed to the end point left that one raw GPS height in charge of the
     * first segment, and turned noise on the flat into a -2 % start (caught by
     * GpxRouteTest). The line keeps a real slope exact right to the end, which
     * an average over a lopsided window would flatten.
     */
    private fun smooth(h: DoubleArray): DoubleArray {
        val k = (SMOOTH_M / STEP_M).toInt()
        val w = min(2 * k + 1, h.size)
        return DoubleArray(h.size) { i ->
            val lo = (i - k).coerceIn(0, h.size - w)
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (j in lo until lo + w) {
                val x = (j - i).toDouble()
                sx += x; sy += h[j]; sxx += x * x; sxy += x * h[j]
            }
            val den = w * sxx - sx * sx
            if (den == 0.0) sy / w
            else (sy * sxx - sx * sxy) / den     // the fitted line's value at x = 0
        }
    }

    private fun heightAt(h: DoubleArray, d: Double): Double {
        val x = d / STEP_M
        val i = x.toInt().coerceIn(0, h.size - 2)
        val t = (x - i).coerceIn(0.0, 1.0)
        return h[i] + (h[i + 1] - h[i]) * t
    }

    /** Haversine; a walk is short enough that the Earth's shape beyond this does not matter. */
    private fun metres(a: Pt, b: Pt): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(min(1.0, h)))
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
}
