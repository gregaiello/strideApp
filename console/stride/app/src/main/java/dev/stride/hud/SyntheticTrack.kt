package dev.stride.hud

/**
 * A made-up GPS track for a walk that went nowhere.
 *
 * Strava treats an activity without positions as an indoor one and ignores
 * the file's altitude, so the treadmill's climb would vanish. Giving it a
 * track fixes that, the same trick Zwift uses. The track is our own circle,
 * not Zwift's roads, and it sits in the patch of ocean off the Solomon
 * Islands where Zwift's Watopia lives: athletes already know points there
 * mean "indoors", and nobody's real run lands on top of it.
 *
 * Tested on Strava (2026-11-03): a 400 m circle here, with a barometric
 * watch's identity, kept the file's 60 m of climb and drew the circle on the
 * map.
 *
 * Position advances by distance walked, so pace on the map matches the belt.
 */
object SyntheticTrack {
    const val CENTRE_LAT = -11.640
    const val CENTRE_LON = 166.950
    const val CIRCUMFERENCE_M = 400.0
    private const val EARTH_R = 6371008.8

    /** @return (latitude, longitude) in degrees, starting due north of the centre. */
    fun at(distanceM: Double): Pair<Double, Double> {
        val r = CIRCUMFERENCE_M / (2 * Math.PI)
        val a = distanceM / r
        val north = r * Math.cos(a)
        val east = r * Math.sin(a)
        val lat = CENTRE_LAT + Math.toDegrees(north / EARTH_R)
        val lon = CENTRE_LON + Math.toDegrees(east / (EARTH_R * Math.cos(Math.toRadians(CENTRE_LAT))))
        return lat to lon
    }
}
