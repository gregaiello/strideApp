package dev.stride.hud

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GpxRouteTest {

    /** Points heading due north every [stepM], height from [ele]. */
    private fun gpx(n: Int, stepM: Double = 10.0, name: String? = "Hill",
                    lonFirst: Boolean = false, ele: (Double) -> Double?): String {
        val degPerM = 1.0 / 111_195.0
        val sb = StringBuilder("<?xml version=\"1.0\"?><gpx version=\"1.1\"><trk>")
        if (name != null) sb.append("<name>").append(name).append("</name>")
        sb.append("<trkseg>")
        for (i in 0 until n) {
            val d = i * stepM
            val lat = 45.0 + d * degPerM
            val attrs = if (lonFirst) "lon=\"9.0\" lat=\"$lat\"" else "lat=\"$lat\" lon=\"9.0\""
            val e = ele(d)
            sb.append("<trkpt $attrs>")
            if (e != null) sb.append("<ele>").append(e).append("</ele>")
            sb.append("<time>2026-11-06T10:00:00Z</time></trkpt>")
        }
        return sb.append("</trkseg></trk></gpx>").toString()
    }

    private fun ok(r: GpxRoute.Result) = (r as GpxRoute.Result.Ok).route

    @Test fun steadyHillIsOneSegmentAtItsGrade() {
        val route = ok(GpxRoute.convert(gpx(101) { it * 0.05 }, "hill.gpx"))
        val segs = route.getJSONArray("segments")
        assertEquals(1, segs.length())
        val s = segs.getJSONArray(0)
        assertEquals(0.0, s.getDouble(0), 0.01)
        assertEquals(1000.0, s.getDouble(1), 1.0)
        assertEquals(5.0, s.getDouble(2), 0.01)
        assertEquals(1000.0, route.getDouble("distance_m"), 1.0)
        assertEquals(50.0, route.getDouble("climb_m"), 1.0)
        assertEquals("Hill", route.getString("name"))
        assertEquals("nas:hill.gpx", route.getString("id"))
        assertEquals("nas", route.getString("source"))
    }

    @Test fun upThenDownBecomesTwoGrades() {
        val route = ok(GpxRoute.convert(gpx(201) { d -> if (d <= 1000) d * 0.04 else 40 - (d - 1000) * 0.02 }, "x.gpx"))
        val segs = route.getJSONArray("segments")
        val first = segs.getJSONArray(0)
        val last = segs.getJSONArray(segs.length() - 1)
        assertEquals(4.0, first.getDouble(2), 0.01)
        assertEquals(-2.0, last.getDouble(2), 0.01)
        assertEquals(2000.0, last.getDouble(1), 1.0)
        // The turn is smoothed over a few segments, not a dozen.
        assertTrue("segments: $segs", segs.length() <= 5)
    }

    /** Phone-grade heights: ±2 m of fresh noise every 5 m, on dead flat ground.
     *  Differenced raw that is ±80 %; the deck must stay within a couple. */
    @Test fun gpsNoiseOnTheFlatDoesNotBecomeRamps() {
        for (seed in 1L..20L) {
            val rnd = Random(seed)
            val route = ok(GpxRoute.convert(gpx(301, stepM = 5.0) { 100 + rnd.nextGaussian() * 2.0 }, "flat.gpx"))
            val segs: JSONArray = route.getJSONArray("segments")
            for (i in 0 until segs.length()) {
                val inc = segs.getJSONArray(i).getDouble(2)
                assertTrue("seed $seed segment $i at $inc %", Math.abs(inc) <= 2.0)
            }
        }
    }

    @Test fun noElevationIsSkippedWithAReason() {
        val r = GpxRoute.convert(gpx(100) { null }, "drawn.gpx")
        assertEquals("no elevation in this file", (r as GpxRoute.Result.Skip).why)
    }

    @Test fun tooShortIsSkipped() {
        assertTrue(GpxRoute.convert(gpx(10) { it * 0.05 }, "s.gpx") is GpxRoute.Result.Skip)
    }

    @Test fun attributeOrderAndMissingNameAreHandled() {
        val route = ok(GpxRoute.convert(gpx(60, name = null, lonFirst = true) { it * 0.03 }, "Morning walk.gpx"))
        assertEquals("Morning walk", route.getString("name"))
        assertEquals(3.0, route.getJSONArray("segments").getJSONArray(0).getDouble(2), 0.01)
    }

    @Test fun routePointsWorkLikeTrackPoints() {
        val text = gpx(60) { it * 0.03 }.replace("trkpt", "rtept").replace("<trk>", "<rte>")
            .replace("</trk>", "</rte>").replace("<trkseg>", "").replace("</trkseg>", "")
        assertTrue(GpxRoute.convert(text, "r.gpx") is GpxRoute.Result.Ok)
    }

    /** The shape Strava exports: metadata first, then points carrying sensor
     *  extensions. The real file that crashed the console had 2722 of these. */
    @Test fun stravaExportWithExtensionsAndMetadata() {
        val degPerM = 1.0 / 111_195.0
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx creator=\"StravaGPX\" version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
            " <metadata>\n  <time>2026-10-31T12:00:11Z</time>\n </metadata>\n" +
            " <trk>\n  <name>Altst\u00e4tter Berglauf</name>\n  <type>running</type>\n  <trkseg>\n")
        for (i in 0 until 2722) {
            val d = i * 3.0
            sb.append("   <trkpt lat=\"").append(47.0 + d * degPerM).append("\" lon=\"9.5\">\n")
                .append("    <ele>").append(466.5 + d * 0.08).append("</ele>\n")
                .append("    <time>2026-10-31T12:00:11Z</time>\n")
                .append("    <extensions>\n     <power>408</power>\n     <gpxtpx:TrackPointExtension>\n")
                .append("      <gpxtpx:hr>108</gpxtpx:hr>\n      <gpxtpx:cad>92</gpxtpx:cad>\n")
                .append("     </gpxtpx:TrackPointExtension>\n    </extensions>\n   </trkpt>\n")
        }
        sb.append("  </trkseg>\n </trk>\n</gpx>\n")
        val route = ok(GpxRoute.convert(sb.toString(), "Altst\u00e4tter_Berglauf_4th.gpx"))
        assertEquals("Altst\u00e4tter Berglauf", route.getString("name"))
        assertEquals(8163.0, route.getDouble("distance_m"), 5.0)
        assertEquals(8.0, route.getJSONArray("segments").getJSONArray(0).getDouble(2), 0.01)
    }
}
