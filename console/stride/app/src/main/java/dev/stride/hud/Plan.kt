package dev.stride.hud

/**
 * What the console walks through: an ordered list of steps, from a route or a
 * structured session.
 *
 * This used to hold four hand-written guided-walk templates (Steady, Rolling
 * hills, Pyramid, The long climb). They went on 2026-11-21 (fork-only): once
 * the treadmill was used for training rather than wandering they were never
 * chosen again, and structured sessions (see [Sessions]) took their place on
 * the setup screen. The step shape stayed because routes and sessions both
 * produce it, and the HUD draws either without knowing which it holds.
 *
 * Two rules a step cannot break, both enforced in `MainActivity`, not here:
 *
 * * **Everything is clamped against what the board says it can do.** A step is
 *   a proposal. The machine's own reported limits win.
 * * **Speed is driven only by a session**, which the walker started with a
 *   tap, and only ever as a ramp. On a route it stays a suggestion.
 */
object Plan {

    /**
     * One step, resolved against real time (a session) or real distance (a
     * route, whose `startSec`/`endSec` are metres; see MainActivity.routeTick).
     *
     * @param paceDelta km/h to suggest relative to a settled baseline; routes only
     * @param kph       the belt speed a session holds at the start of this step,
     *                  or 0 when the step does not drive speed (a route)
     * @param endIncline / endKph  where a progression has ramped to by the end of
     *                  the step; equal to the start values on a flat step
     */
    data class Step(
        val startSec: Double,
        val endSec: Double,
        val incline: Double,
        val paceDelta: Double,
        val label: String,
        val note: String,
        val kph: Double = 0.0,
        val endIncline: Double = incline,
        val endKph: Double = kph,
    ) {
        val seconds: Double get() = endSec - startSec

        /** Grade at [t] seconds into the plan, interpolated across a ramp. */
        fun inclineAt(t: Double): Double = lerp(incline, endIncline, t)

        /** Belt speed at [t] seconds into the plan, interpolated across a ramp. */
        fun kphAt(t: Double): Double = lerp(kph, endKph, t)

        private fun lerp(a: Double, b: Double, t: Double): Double {
            if (a == b || seconds <= 0.0) return a
            val f = ((t - startSec) / seconds).coerceIn(0.0, 1.0)
            return a + (b - a) * f
        }
    }
}
