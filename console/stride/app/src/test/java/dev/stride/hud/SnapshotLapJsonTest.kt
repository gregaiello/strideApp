package dev.stride.hud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SnapshotLapJsonTest {

    private fun snap() = Snapshot(
        speed = 9.0, incline = 2.5, targetSpeed = 9.0, targetIncline = 2.5,
        distance = 1250.4, elapsed = 600.2, calories = 80.0, pulse = 150, fan = 0,
        session = Session.ACTIVE, workout = "", dmk = false, ramping = "", phaseLeft = 0.0,
        boardMode = 2, avgSpeed = 8.9, maxSpeed = 9.5, avgIncline = 1.5, maxIncline = 6.0,
        lap = 3, lapElapsed = 61.4, lapDistance = 250.6, lapHr = 141, lapClimb = 3.26, lapFlash = 2, dateUnknown = true,
        laps = listOf(Lap(1, 0, 60_000, 60.0, 250.0, 150, 5.0, 0.0)),
    )

    private fun check(json: JSONObject) {
        assertEquals(3, json.getInt("lap"))
        assertEquals(61, json.getInt("lapElapsed"))
        assertEquals(251, json.getInt("lapDistance"))
        assertEquals(2, json.getInt("lapFlash"))
        assertEquals(141, json.getInt("lapHr"))
        assertEquals(3.3, json.getDouble("lapClimb"), 1e-9)
        assertTrue(json.getBoolean("dateUnknown"))
        val lap = json.getJSONArray("laps").getJSONObject(0)
        assertEquals(1, lap.getInt("i"))
        assertEquals(60, lap.getInt("sec"))
        assertEquals(250, lap.getInt("m"))
        assertEquals(150, lap.getInt("hr"))
        assertEquals(5, lap.getInt("up"))
    }

    @Test fun lapFieldsSerialise() = check(JSONObject(snap().toJson()))

    /** A console set to German or Italian writes 9,0 for "%.1f" — not JSON. */
    @Test fun parsesUnderGermanLocale() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val json = JSONObject(snap().toJson())
            check(json)
            assertEquals(9.0, json.getDouble("speed"), 0.0)
        } finally {
            Locale.setDefault(was)
        }
    }
}
