package dev.stride.hud

/**
 * Distance that only ever counts forward.
 *
 * The board resets its own odometer mid-session now and then (see
 * MainActivity.accumulate), and the session distance drops to about zero with
 * it. A recording that followed it would carry a distance going backwards into
 * the .FIT, and a 40-minute walk that ended "300 m" after a reset would be
 * thrown away as too short. Summing the positive steps loses one sample's worth
 * of metres at a reset instead.
 */
class Odometer {
    private var last = 0.0

    var total = 0.0
        private set

    /** @return the running total after taking in this reading. */
    fun update(reading: Double): Double {
        val d = reading - last
        if (d > 0) total += d
        last = reading
        return total
    }
}
