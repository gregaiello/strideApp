package dev.stride.hud

import java.util.Locale

/**
 * Where the *app* thinks we are in a workout.
 *
 * Deliberately not the board's `WorkoutMode`. The board's state machine is
 * one-way — it will go Idle → Running but refuses Running → Idle — so a workout
 * that has plainly finished still reports `running` for hours afterwards. Home
 * Assistant then believes the treadmill has been in use since breakfast, and the
 * elapsed counter never returns to zero.
 *
 * The user pressing START and END is the fact we can actually trust, so that is
 * what drives the HUD, the timer and the recording. The board's
 * mode is kept only for the two things it alone knows: the belt interlock and
 * the safety key.
 */
object Session {
    const val WELCOME = 0    // "what are we doing today?"
    const val WARMUP = 1     // easing in at walking pace, skippable
    const val ACTIVE = 2     // the workout proper
    const val PAUSED = 3     // belt stopped, timer held
    const val COOLDOWN = 4   // easing back down, skippable
    const val SUMMARY = 5    // post-workout recap

    fun name(v: Int) = when (v) {
        WELCOME -> "welcome"; WARMUP -> "warmup"; ACTIVE -> "running"
        PAUSED -> "paused"; COOLDOWN -> "cooldown"; SUMMARY -> "summary"
        else -> "unknown"
    }

    /** States in which the belt is live and the clock is running. */
    fun isMoving(v: Int) = v == WARMUP || v == ACTIVE || v == COOLDOWN
}

/** One frame of everything the HUD and the recording need. */
data class Snapshot(
    /**
     * The pace the console is holding, km/h — the setpoint, not a measurement.
     *
     * This is the number on the dial, and it is the commanded speed on purpose.
     * `ActualKph` never populates on this board (see MainActivity.beltSpeed), so
     * the only alternative is an estimate built from a whole-metre odometer, and
     * showing that estimate is what made the readout wander. On 17 August 2026 a
     * steady walk rendered nineteen different speeds in thirty-six seconds while
     * the deck was tilting — 7.9, 7.7, 7.6, 7.5, 7.4, 7.5, 7.6, 7.7, 7.6 … — and
     * pressing − at 7.6 landed on 7.4 because the drift was larger than the step.
     *
     * A belt is a closed loop that reaches what it is told within a second or
     * two, so the setpoint is both the honest answer and the stable one, and it
     * makes the buttons exact by construction: 7.6 − 0.1 is 7.5, always. The
     * estimate is still computed, and still allowed to disagree — it is carried
     * as [beltKph] and watched, rather than being put on the dial.
     */
    val speed: Double,
    /**
     * What the odometer says the belt is actually doing, km/h.
     *
     * Diagnosis only: nothing draws this. It exists so a belt that cannot reach
     * the commanded pace is still detectable — see [slipping] — now that the
     * dial no longer shows it.
     *
     * Reconstructing one walk's belt speed afterwards (8 September 2026) meant
     * differencing the whole-metre distance sensor, because this — the number
     * the console had already worked out — was going nowhere. It was published
     * to Home Assistant from then on; this fork has no Home Assistant, so it is
     * logged and carried here only.
     */
    val beltKph: Double = 0.0,
    /**
     * The board's own `RPM` field, raw and unscaled.
     *
     * Read on the chance that it is a *different* sensor from the one behind
     * `DISTANCE`. If it is, the two disagreeing is the only signal this console
     * could ever have for a belt slipping over the drive roller: the odometer
     * counts the roller, so it and [beltKph] both sit upstream of that slip and
     * neither can see it. See [slipping] for why that matters.
     *
     * May well read a flat zero, as `ACTUAL_KPH` does on this board. It is
     * recorded rather than trusted, and nothing is built on it until a walk has
     * shown what it does.
     */
    val rpm: Double = 0.0,
    val incline: Double,
    val targetSpeed: Double,
    val targetIncline: Double,
    val distance: Double,
    val elapsed: Double,
    val calories: Double,
    val pulse: Int,
    val fan: Int,
    val session: Int,
    val workout: String,
    val dmk: Boolean,
    /** "warmup", "resuming", "cooldown", or empty when no ramp is in flight. */
    val ramping: String,
    /** Seconds left in a timed phase (warm-up / cool-down); 0 otherwise. */
    val phaseLeft: Double,
    /** The board's own WorkoutMode, carried purely for diagnosis. 8 = safety key out. */
    val boardMode: Int,
    val avgSpeed: Double,
    val maxSpeed: Double,
    val avgIncline: Double,
    val maxIncline: Double,
    /** Averaged over the frames that had a reading, not over the walk — a
     *  strap that connected late or dropped out must not drag the number
     *  towards zero. Both are 0 when nothing ever read. */
    val avgPulse: Int = 0,
    val maxPulse: Int = 0,
    /**
     * The *walker's* maximum heart rate, or 0 when they have given neither a
     * maximum nor an age.
     *
     * Not [maxPulse], which is the highest beat seen during this walk. This one
     * is a property of the person — see Settings.Person.maxPulse.
     */
    val hrMax: Int = 0,
    /**
     * The bpm zones 1-5 start at for this walker, or empty when they have no
     * zones (no maximum, no age, none typed in). Sent every frame so the HUD
     * can draw the zone gauge without knowing how they were worked out.
     */
    val zoneFloors: List<Int> = emptyList(),
    /** Which zone [pulse] is in right now, 1-5; 0 below zone 1, or no zones. */
    val zone: Int = 0,
    /**
     * Estimated power, watts, or 0 when it cannot be estimated (no weight
     * given, belt stopped). An estimate from weight, speed and grade — see
     * Power.kt — not a measurement; nothing on this treadmill measures force.
     */
    val power: Int = 0,
    /** The same estimate averaged over the moving time of the lap so far. */
    val lapPower: Int = 0,
    /**
     * Seconds spent in each heart-rate zone, indexed 1-5; index 0 is everything
     * below zone 1 and is counted but rarely interesting.
     *
     * Empty when [hrMax] is 0 — without a maximum there are no zones, and a
     * console that invented some would be dressing a guess as physiology. The
     * summary falls back to average and peak, which need nobody's age.
     */
    val zoneSecs: List<Int> = emptyList(),
    /**
     * What this walk did that previous walks did not — see History.
     *
     * Computed once, when the belt stops, against this walker's own recorded
     * sessions. Empty on the way round; empty too for a first walk, which has
     * nothing to beat and should not be told it set five records.
     */
    val achievements: List<String> = emptyList(),
    /** Who tapped their name on the welcome screen. */
    val who: String = "",
    /** Guided walk, or empty on a casual one. */
    val plan: String = "",
    val segment: Int = 0,
    val segments: Int = 0,
    val segmentLabel: String = "",
    val segmentLeft: Double = 0.0,
    /** True when [segmentLeft] is metres rather than seconds — a route is
     *  driven by distance, so "how much of this stretch is left" is a distance
     *  too. See MainActivity.routeTick. */
    val segmentLeftIsDistance: Boolean = false,
    /** The segment after this one, so the HUD can warn before it arrives. */
    val nextLabel: String = "",
    val nextIncline: Double = 0.0,
    /** Total plan length in seconds — one lap on a circuit. */
    val planTotalSec: Double = 0.0,
    /** Total ascent of the plan in metres, when it is known — a route carries
     *  its own, measured from the walk it came from. Zero for templates, whose
     *  climb depends on how fast you take them. */
    val planClimbM: Double = 0.0,
    /** The steepest gradient anywhere in the plan. */
    val planPeakIncline: Double = 0.0,
    /** Plan time wrapped into the current lap. Equals elapsed unless looping. */
    val planElapsed: Double = 0.0,
    /** Which lap of an open-ended circuit; 1 for an ordinary walk. */
    val planLap: Int = 1,
    /** True when the route is a circuit with no end but STOP. */
    val planLoops: Boolean = false,
    /** Pace the plan is suggesting, km/h. 0 when it has no opinion. */
    val suggestPace: Double = 0.0,
    /** False once a manual incline press has taken the deck back for this segment. */
    val inclineAuto: Boolean = false,
    /** False while the board is refusing frames. The numbers here are then the
     *  last good ones rather than current, and the page can say so instead of
     *  presenting stale values as live. The console keeps drawing either way:
     *  repainting only after a good frame froze the HUD mid-render on
     *  2026-08-07 and made every button look dead. */
    val boardOk: Boolean = true,
    /** True while the board is refusing everything pending VerifySecurity. A
     *  different thing from [boardOk] being false, and worth saying differently:
     *  one is "the board is not answering", the other is "the board wants us to
     *  authenticate and we are doing it". */
    val boardLocked: Boolean = false,
    /** The lap in progress, 1-based; 0 while there is no workout to lap. */
    val lap: Int = 0,
    /** Active seconds in the current lap, paused time excluded. */
    val lapElapsed: Double = 0.0,
    val lapDistance: Double = 0.0,
    /** The lap in progress, for its own row on the running screen: average
     *  pulse while moving (0 = none read yet) and metres climbed. */
    val lapHr: Int = 0,
    val lapClimb: Double = 0.0,
    /** Closed laps. Filled on the summary only, to keep the 5 Hz frame small. */
    val laps: List<Lap> = emptyList(),
    /** For a few seconds after a press, the lap it closed, so a mid-stride tap
     *  can be seen to have landed; 0 otherwise. */
    val lapFlash: Int = 0,
    /** Summary only: the console clock was never trustworthy during this walk,
     *  so whatever date its files carry is a guess. See sessionStartWall. */
    val dateUnknown: Boolean = false,
) {
    val mode: String get() = Session.name(session)

    /**
     * True when the ground says the belt is well short of what was asked for.
     *
     * The dial shows the setpoint, which is right almost always and wrong in
     * exactly one case: a belt that physically cannot reach the pace. This is
     * how that case still gets noticed. Deliberately a wide band — the estimate
     * behind it is coarse, and a threshold tight enough to catch a small
     * shortfall would fire on the estimator's own noise.
     *
     * **What this cannot catch.** It compares the setpoint against [beltKph],
     * and [beltKph] comes off the odometer, which counts the *drive roller*.
     * Both numbers therefore sit on the same side of a belt that is slipping
     * over that roller: the motor holds its speed, the odometer reports it
     * faithfully, and the belt surface under the walker sags and then catches
     * up without either number moving. On 8 September 2026 exactly that was
     * felt at the top of the last climb, and a second-by-second reconstruction
     * of the whole descent showed the roller never left 10.0 km/h. This flag
     * would not have fired, and did not.
     *
     * So it is a motor-cannot-reach-pace detector, not a slip detector, and the
     * name is a promise it only half keeps. [rpm] is the candidate for the
     * other half. Until that proves out, the honest record of a slip is the
     * walker saying so.
     */
    val slipping: Boolean
        get() = Session.isMoving(session) && speed > 0.5 && beltKph > 0.0 &&
                speed - beltKph > 0.8

    /**
     * JSON string escaping, for the fields that carry text somebody else wrote.
     *
     * Plan and segment labels come from a route file, and names are typed in
     * by hand. Either of them may contain a quote or a backslash, and one of those dropped
     * raw into this object produces JSON the HUD cannot parse — a blank screen
     * for the rest of the walk, from one apostrophe.
     */
    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    /**
     * Numbers for the page, always with a decimal point.
     *
     * `"%.1f".format` follows the console's locale, and on one set to German
     * or Italian that writes 9,0 — which is not JSON, so the page would get
     * nothing it could parse and the HUD would stop updating.
     */
    private fun num(fmt: String, v: Double): String = String.format(Locale.US, fmt, v)

    fun toJson(): String = buildString {
        append("{")
        append("\"speed\":${num("%.1f", speed)},")
        append("\"beltKph\":${num("%.1f", beltKph)},")
        append("\"rpm\":${num("%.0f", rpm)},")
        append("\"slipping\":$slipping,")
        append("\"incline\":${num("%.1f", incline)},")
        append("\"targetSpeed\":${num("%.1f", targetSpeed)},")
        append("\"targetIncline\":${num("%.1f", targetIncline)},")
        append("\"distance\":${num("%.0f", distance)},")
        append("\"elapsed\":${num("%.0f", elapsed)},")
        append("\"calories\":${num("%.0f", calories)},")
        append("\"pulse\":$pulse,")
        append("\"fan\":$fan,")
        append("\"workout\":\"${esc(workout)}\",")
        append("\"dmk\":$dmk,")
        append("\"ramping\":\"${esc(ramping)}\",")
        append("\"phaseLeft\":${num("%.0f", phaseLeft)},")
        append("\"boardMode\":$boardMode,")
        append("\"boardOk\":$boardOk,")
        append("\"boardLocked\":$boardLocked,")
        append("\"avgSpeed\":${num("%.1f", avgSpeed)},")
        append("\"maxSpeed\":${num("%.1f", maxSpeed)},")
        append("\"avgIncline\":${num("%.1f", avgIncline)},")
        append("\"maxIncline\":${num("%.1f", maxIncline)},")
        append("\"avgPulse\":$avgPulse,")
        append("\"maxPulse\":$maxPulse,")
        append("\"hrMax\":$hrMax,")
        append("\"zoneSecs\":${zoneSecs.joinToString(",", "[", "]")},")
        append("\"zoneFloors\":${zoneFloors.joinToString(",", "[", "]")},")
        append("\"zone\":$zone,")
        append("\"power\":$power,")
        append("\"lapPower\":$lapPower,")
        append("\"achievements\":${
            achievements.joinToString(",", "[", "]") { "\"${esc(it)}\"" }
        },")
        append("\"who\":\"${esc(who)}\",")
        append("\"plan\":\"${esc(plan)}\",")
        append("\"segment\":$segment,")
        append("\"segments\":$segments,")
        append("\"segmentLabel\":\"${esc(segmentLabel)}\",")
        append("\"segmentLeft\":${num("%.0f", segmentLeft)},")
        append("\"segmentLeftIsDistance\":$segmentLeftIsDistance,")
        append("\"nextLabel\":\"${esc(nextLabel)}\",")
        append("\"nextIncline\":${num("%.1f", nextIncline)},")
        append("\"planTotalSec\":${num("%.0f", planTotalSec)},")
        append("\"planClimbM\":${num("%.0f", planClimbM)},")
        append("\"planPeakIncline\":${num("%.1f", planPeakIncline)},")
        append("\"planElapsed\":${num("%.0f", planElapsed)},")
        append("\"planLap\":$planLap,")
        append("\"planLoops\":$planLoops,")
        append("\"suggestPace\":${num("%.1f", suggestPace)},")
        append("\"inclineAuto\":$inclineAuto,")
        append("\"lap\":$lap,")
        append("\"lapElapsed\":${num("%.0f", lapElapsed)},")
        append("\"lapDistance\":${num("%.0f", lapDistance)},")
        append("\"lapHr\":$lapHr,")
        append("\"lapClimb\":${num("%.1f", lapClimb)},")
        append("\"lapFlash\":$lapFlash,")
        append("\"dateUnknown\":$dateUnknown,")
        append("\"laps\":${laps.joinToString(",", "[", "]") {
            "{\"i\":${it.index},\"sec\":${num("%.0f", it.activeSec)}," +
                "\"m\":${num("%.0f", it.distanceM)},\"hr\":${it.avgHr}," +
                "\"w\":${it.avgPower}," +
                "\"up\":${num("%.0f", it.ascentM)}}"
        }},")
        append("\"mode\":\"$mode\"")
        append("}")
    }
}
