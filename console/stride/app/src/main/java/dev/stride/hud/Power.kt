package dev.stride.hud

/**
 * Walking/running power, estimated from weight, belt speed and grade.
 *
 * Nothing on this treadmill measures force, so this is a model, and a simple
 * one on purpose: the flat cost of moving a body along the ground plus the
 * work of lifting it up the grade,
 *
 *     P = m · v · (C + g · grade)
 *
 * with C ≈ 1.0 J/kg/m, the flat-ground cost that running-power pods and
 * watches settle on (about 1 W/kg per m/s). It is the same shape those
 * devices use, so a number seen here is roughly comparable with one seen
 * outdoors on a watch, and it moves the way effort does: +1% of incline at
 * 5 km/h on an 80 kg walker is about +11 W.
 *
 * Downhill is floored at the flat cost's lower part rather than allowed to go
 * to zero or negative: walking down a 10% slope is not free, and a power
 * figure that said 0 W while the walker was working would be worse than none.
 * The floor is crude, and the whole thing over-reads slow walking a little —
 * walking is cheaper than running at the same speed below about 7 km/h — which
 * is the trade for one formula that works across the treadmill's range.
 */
object Power {
    private const val G = 9.81
    /** Flat-ground cost, J per kg per metre. */
    private const val FLAT = 1.0
    /** Never less than this share of the flat cost, whatever the slope. */
    private const val DOWNHILL_FLOOR = 0.5

    /**
     * @param kph   belt speed, km/h
     * @param grade incline in percent (the board's units)
     * @return watts, or 0 when the walker has no weight or the belt is stopped
     */
    fun watts(weightKg: Double, kph: Double, grade: Double): Double {
        if (weightKg <= 0 || kph <= 0) return 0.0
        val v = kph / 3.6
        val perKgPerM = (FLAT + G * grade / 100.0).coerceAtLeast(FLAT * DOWNHILL_FLOOR)
        return weightKg * v * perKgPerM
    }
}
