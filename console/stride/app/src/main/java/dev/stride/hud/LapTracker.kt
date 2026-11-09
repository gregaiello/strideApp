package dev.stride.hud

/**
 * One closed lap, as a sport watch would report it.
 *
 * [startMs]/[endMs] are monotonic offsets from the start of the session, not
 * wall-clock times. The console's wall clock can jump by years when NTP lands
 * mid-walk (see MainActivity.sessionStartWall), so anything that has to line
 * up with the recording is kept on the clock that cannot jump, and dated once,
 * at the end.
 */
data class Lap(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    /** Paused time excluded — a lap is time spent moving, as on a watch. */
    val activeSec: Double,
    val distanceM: Double,
    /** 0 means nothing was read during the lap, never "a pulse of zero". */
    val avgHr: Int,
    val ascentM: Double,
    val descentM: Double,
    /** Estimated watts over the lap's moving time; 0 when there was no estimate. */
    val avgPower: Int = 0,
)

/** The lap in progress, for the running screen. [avgHr] 0 means no pulse yet. */
data class LapLive(
    val index: Int,
    val activeSec: Double,
    val distanceM: Double,
    val avgHr: Int = 0,
    val ascentM: Double = 0.0,
    val avgPower: Int = 0,
)

/**
 * Lap marks for structured training: a button that closes one lap and opens
 * the next.
 *
 * **A lap is a mark and nothing else.** It never touches speed, incline or the
 * session. Intervals on this treadmill are run by the person on it — speed is
 * only ever suggested here, and the person decides when to go faster — so the
 * lap button records what they did rather than telling the belt what to do.
 *
 * Distance is summed from positive steps rather than taken as end − start,
 * because the board resets its own odometer mid-session now and then (see
 * MainActivity.accumulate). A subtraction across that reset would give a
 * negative lap; summing steps loses one poll's worth of metres instead.
 *
 * Called from the poll thread (samples) and a WebView thread (the press), so
 * every entry point is synchronised.
 */
class LapTracker {

    companion object {
        /**
         * A second press closer than this to the last boundary is the same
         * press. Mid-stride taps bounce; a two-second lap is never meant.
         */
        const val MIN_LAP_SEC = 2.0
    }

    private var started = false
    private var index = 0
    private var startMs = 0L
    private var startActive = 0.0
    private var distance = 0.0
    private var lastDistance = 0.0
    private var lastAltitude = 0.0
    private var ascent = 0.0
    private var descent = 0.0
    private var hrSum = 0L
    private var hrN = 0
    /** Joules and the moving seconds they were spread over — time-weighted
     *  rather than averaged per sample, so a late or doubled sample cannot
     *  tilt the lap. */
    private var energyJ = 0.0
    private var powerSec = 0.0
    private var lastSampleMs = -1L
    private val closed = mutableListOf<Lap>()

    @Synchronized fun start(tMs: Long, activeSec: Double, distanceM: Double, altitudeM: Double) {
        reset()
        started = true
        index = 1
        lastDistance = distanceM
        lastAltitude = altitudeM
        open(tMs, activeSec)
    }

    /**
     * @param moving false while paused: the seconds still pass through so the
     *               distance and altitude stay in step, but a resting pulse is
     *               not counted as effort in a lap whose time leaves the pause out.
     * @param watts  the power estimate at this sample, 0 when there is none
     *               (a walker with no weight). Such a lap reports 0, not a low
     *               average.
     */
    @Synchronized fun sample(tMs: Long, activeSec: Double, distanceM: Double, hr: Int, altitudeM: Double,
                             moving: Boolean = true, watts: Double = 0.0) {
        if (!started) return
        step(distanceM)
        val dAlt = altitudeM - lastAltitude
        if (dAlt > 0) ascent += dAlt else descent -= dAlt
        lastAltitude = altitudeM
        if (hr > 0 && moving) { hrSum += hr; hrN++ }
        // Capped at a few seconds: a gap longer than that is a stall in the
        // sampler, and the watts either side of it say nothing about it.
        val dt = if (lastSampleMs >= 0) ((tMs - lastSampleMs) / 1000.0).coerceIn(0.0, 5.0) else 0.0
        lastSampleMs = tMs
        if (moving && watts > 0) { energyJ += watts * dt; powerSec += dt }
    }

    /** @return the lap just closed, or null if the press was ignored. */
    @Synchronized fun mark(tMs: Long, activeSec: Double, distanceM: Double): Lap? {
        if (!started) return null
        if (activeSec - startActive < MIN_LAP_SEC) return null
        val lap = close(tMs, activeSec, distanceM)
        index++
        open(tMs, activeSec)
        return lap
    }

    /** Closes the last lap. Null if never started, or already finished. */
    @Synchronized fun finish(tMs: Long, activeSec: Double, distanceM: Double): Lap? {
        if (!started) return null
        val lap = close(tMs, activeSec, distanceM)
        started = false
        return lap
    }

    @Synchronized fun live(activeSec: Double, distanceM: Double): LapLive? {
        if (!started) return null
        val pending = (distanceM - lastDistance).coerceAtLeast(0.0)
        return LapLive(index, (activeSec - startActive).coerceAtLeast(0.0), distance + pending,
            avgHr = if (hrN > 0) (hrSum / hrN).toInt() else 0, ascentM = ascent,
            avgPower = avgPower())
    }

    @Synchronized fun closed(): List<Lap> = closed.toList()

    @Synchronized fun reset() {
        started = false
        index = 0
        closed.clear()
        lastSampleMs = -1L
        open(0L, 0.0)
    }

    private fun step(distanceM: Double) {
        val d = distanceM - lastDistance
        if (d > 0) distance += d
        lastDistance = distanceM
    }

    private fun close(tMs: Long, activeSec: Double, distanceM: Double): Lap {
        step(distanceM)
        val lap = Lap(
            index = index,
            startMs = startMs,
            endMs = tMs,
            activeSec = (activeSec - startActive).coerceAtLeast(0.0),
            distanceM = distance,
            avgHr = if (hrN > 0) (hrSum / hrN).toInt() else 0,
            ascentM = ascent,
            descentM = descent,
            avgPower = avgPower(),
        )
        closed.add(lap)
        return lap
    }

    private fun open(tMs: Long, activeSec: Double) {
        startMs = tMs
        startActive = activeSec
        distance = 0.0
        ascent = 0.0
        descent = 0.0
        hrSum = 0L
        hrN = 0
        energyJ = 0.0
        powerSec = 0.0
    }

    private fun avgPower(): Int = if (powerSec > 0) Math.round(energyJ / powerSec).toInt() else 0
}
