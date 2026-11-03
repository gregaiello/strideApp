package dev.stride.hud

import java.util.TimeZone

/**
 * Who the file says recorded it: FIT's manufacturer and product ids.
 *
 * Strava decides from these whether to believe a file's altitude, so this is
 * not cosmetic (see [FitActivity]).
 */
data class FitIdentity(val manufacturer: Int, val product: Int) {
    companion object {
        /** FIT's "development" manufacturer, for a file no watch made. */
        val GENERIC = FitIdentity(255, 0)

        /**
         * Garmin fenix 7. Fork-only: this claims the file came from a watch
         * it did not come from. Strava keeps a file's altitude only from
         * devices it trusts to have a barometer; in the 2026-11-03 test this
         * was the one identity, of five tried, for which the treadmill's 60 m
         * of climb survived. Upstream ships GENERIC alone.
         */
        val BAROMETRIC = FitIdentity(1, 3906)
    }
}

/**
 * A recorded walk as a FIT activity file, for Strava.
 *
 * **What Strava does with it** (tested 2026-11-03, one file per variant):
 * with positions and a device it trusts to have a barometer, it keeps the
 * file's altitude, so the climb the incline produced survives. With any other
 * device it replaces the altitude with its own terrain map's (hundreds of
 * metres of made-up hills on a 60 m walk) or drops it. Without positions it
 * treats the walk as indoor and keeps no elevation at all. Sub-sport
 * treadmill or virtual activity also lost the elevation, so the file says
 * plain running. The positions come from [SyntheticTrack].
 *
 * **Pauses** are not recorded as points: a paused belt is not on the map.
 * The timer stops at the first paused second and starts at the next moving
 * one, so Strava's moving time is the console's active time.
 *
 * **Times.** Samples carry monotonic offsets; the whole walk is dated once
 * from the corrected start, or, when the console never learnt the date, from
 * the start it believed (the summary has already said the date is unknown).
 */
object FitActivity {

    private const val FILE_ID = 0
    private const val DEVICE_INFO = 23
    private const val EVENT = 21
    private const val RECORD = 20
    private const val LAP = 19
    private const val SESSION = 18
    private const val ACTIVITY = 34

    private const val SPORT_RUNNING = 1L
    private const val SUB_SPORT_GENERIC = 0L
    private const val EVENT_TIMER = 0L
    private const val EVENT_LAP = 9L
    private const val EVENT_SESSION = 8L
    private const val EVENT_ACTIVITY = 26L
    private const val TYPE_START = 0L
    private const val TYPE_STOP = 1L
    private const val TYPE_STOP_ALL = 4L
    private const val TRIGGER_MANUAL = 0L
    private const val TRIGGER_SESSION_END = 7L
    /** "STRM" — the file needs a serial; this one is the same for every console. */
    private const val SERIAL = 0x5354524DL

    fun encode(rec: Recorded, identity: FitIdentity = FitIdentity.GENERIC,
               zone: TimeZone = TimeZone.getDefault()): ByteArray {
        val startWall = if (rec.correctedStartWallMs > 0) rec.correctedStartWallMs else rec.startWallMs
        val samples = rec.samples
        val t0 = FitWriter.fitTime(startWall)
        fun at(tMs: Long) = t0 + tMs / 1000
        val endMs = samples.lastOrNull()?.tMs ?: 0L
        val w = FitWriter()

        w.define(0, FILE_ID, listOf(
            FitField(0, 1, FitBase.ENUM), FitField(1, 2, FitBase.UINT16), FitField(2, 2, FitBase.UINT16),
            FitField(3, 4, FitBase.UINT32Z), FitField(4, 4, FitBase.UINT32)))
        w.data(0, 4L, identity.manufacturer.toLong(), identity.product.toLong(), SERIAL, t0)

        w.define(1, DEVICE_INFO, listOf(
            FitField(253, 4, FitBase.UINT32), FitField(0, 1, FitBase.UINT8), FitField(2, 2, FitBase.UINT16),
            FitField(3, 4, FitBase.UINT32Z), FitField(4, 2, FitBase.UINT16)))
        w.data(1, t0, 0L, identity.manufacturer.toLong(), SERIAL, identity.product.toLong())

        w.define(2, EVENT, listOf(
            FitField(253, 4, FitBase.UINT32), FitField(0, 1, FitBase.ENUM), FitField(1, 1, FitBase.ENUM)))
        w.define(3, RECORD, listOf(
            FitField(253, 4, FitBase.UINT32), FitField(0, 4, FitBase.SINT32), FitField(1, 4, FitBase.SINT32),
            FitField(2, 2, FitBase.UINT16), FitField(3, 1, FitBase.UINT8), FitField(5, 4, FitBase.UINT32),
            FitField(6, 2, FitBase.UINT16)))

        w.data(2, at(samples.firstOrNull()?.tMs ?: 0L), EVENT_TIMER, TYPE_START)
        var running = true
        for (s in samples) {
            if (!s.moving) {
                if (running) { w.data(2, at(s.tMs), EVENT_TIMER, TYPE_STOP_ALL); running = false }
                continue
            }
            if (!running) { w.data(2, at(s.tMs), EVENT_TIMER, TYPE_START); running = true }
            val (lat, lon) = SyntheticTrack.at(s.distanceM)
            w.data(3, at(s.tMs), FitWriter.semicircles(lat), FitWriter.semicircles(lon),
                altitude(s.altitudeM), hr(s.hr), Math.round(s.distanceM * 100), speed(s.speedKph))
        }
        // Already stopped by a pause that ran to the end: one stop is enough.
        if (running) w.data(2, at(endMs), EVENT_TIMER, TYPE_STOP_ALL)

        val laps = laps(rec)
        w.define(4, LAP, listOf(
            FitField(254, 2, FitBase.UINT16), FitField(253, 4, FitBase.UINT32), FitField(0, 1, FitBase.ENUM),
            FitField(1, 1, FitBase.ENUM), FitField(2, 4, FitBase.UINT32), FitField(7, 4, FitBase.UINT32),
            FitField(8, 4, FitBase.UINT32), FitField(9, 4, FitBase.UINT32), FitField(15, 1, FitBase.UINT8),
            FitField(21, 2, FitBase.UINT16), FitField(22, 2, FitBase.UINT16), FitField(24, 1, FitBase.ENUM),
            FitField(25, 1, FitBase.ENUM), FitField(39, 1, FitBase.ENUM)))
        laps.forEachIndexed { i, l ->
            w.data(4, i.toLong(), at(l.endMs), EVENT_LAP, TYPE_STOP, at(l.startMs),
                (l.endMs - l.startMs).coerceAtLeast(0), Math.round(l.activeSec * 1000),
                Math.round(l.distanceM * 100), hr(l.avgHr), Math.round(l.ascentM), Math.round(l.descentM),
                if (i == laps.lastIndex) TRIGGER_SESSION_END else TRIGGER_MANUAL, SPORT_RUNNING, SUB_SPORT_GENERIC)
        }

        val moving = samples.filter { it.moving }
        val distance = samples.lastOrNull()?.distanceM ?: 0.0
        val active = samples.lastOrNull()?.activeSec ?: 0.0
        val (ascent, descent) = climb(samples)
        val hrs = moving.map { it.hr }.filter { it > 0 }
        w.define(5, SESSION, listOf(
            FitField(254, 2, FitBase.UINT16), FitField(253, 4, FitBase.UINT32), FitField(0, 1, FitBase.ENUM),
            FitField(1, 1, FitBase.ENUM), FitField(2, 4, FitBase.UINT32), FitField(5, 1, FitBase.ENUM),
            FitField(6, 1, FitBase.ENUM), FitField(7, 4, FitBase.UINT32), FitField(8, 4, FitBase.UINT32),
            FitField(9, 4, FitBase.UINT32), FitField(11, 2, FitBase.UINT16), FitField(14, 2, FitBase.UINT16),
            FitField(15, 2, FitBase.UINT16), FitField(16, 1, FitBase.UINT8), FitField(17, 1, FitBase.UINT8),
            FitField(22, 2, FitBase.UINT16), FitField(23, 2, FitBase.UINT16), FitField(25, 2, FitBase.UINT16),
            FitField(26, 2, FitBase.UINT16), FitField(28, 1, FitBase.ENUM)))
        w.data(5, 0L, at(endMs), EVENT_SESSION, TYPE_STOP, t0, SPORT_RUNNING, SUB_SPORT_GENERIC,
            endMs, Math.round(active * 1000), Math.round(distance * 100),
            Math.round(rec.calories).coerceIn(0, 0xFFFE),
            if (active > 0) speedMps(distance / active) else null,
            moving.maxOfOrNull { it.speedKph }?.let { speed(it) },
            if (hrs.isEmpty()) null else hr(hrs.sum() / hrs.size),
            hrs.maxOrNull()?.let { hr(it) },
            Math.round(ascent), Math.round(descent), 0L, laps.size.toLong(), 0L)

        w.define(6, ACTIVITY, listOf(
            FitField(253, 4, FitBase.UINT32), FitField(0, 4, FitBase.UINT32), FitField(1, 2, FitBase.UINT16),
            FitField(2, 1, FitBase.ENUM), FitField(3, 1, FitBase.ENUM), FitField(4, 1, FitBase.ENUM),
            FitField(5, 4, FitBase.UINT32)))
        val end = at(endMs)
        val offsetS = zone.getOffset(startWall + endMs) / 1000L
        w.data(6, end, Math.round(active * 1000), 1L, 0L, EVENT_ACTIVITY, TYPE_STOP, end + offsetS)

        return w.bytes()
    }

    /**
     * The recorded laps, completed. A walk the app died in never closed its
     * last lap, and a walk nobody pressed LAP in may have none; either way the
     * remainder becomes a final lap, from the samples after the last one.
     */
    private fun laps(rec: Recorded): List<Lap> {
        val samples = rec.samples
        val last = samples.lastOrNull() ?: return rec.laps
        val closed = rec.laps
        val closedEnd = closed.lastOrNull()?.endMs ?: Long.MIN_VALUE
        if (closed.isNotEmpty() && closedEnd >= last.tMs) return closed
        val rest = samples.filter { it.tMs > closedEnd }
        val startMs = if (closed.isEmpty()) samples.first().tMs else closedEnd
        val hrs = rest.filter { it.moving && it.hr > 0 }.map { it.hr }
        val before = samples.lastOrNull { it.tMs <= closedEnd }
        val (up, down) = climb(listOfNotNull(before) + rest)
        return closed + Lap(
            index = (closed.lastOrNull()?.index ?: 0) + 1,
            startMs = startMs,
            endMs = last.tMs,
            activeSec = (last.activeSec - closed.sumOf { it.activeSec }).coerceAtLeast(0.0),
            distanceM = (last.distanceM - closed.sumOf { it.distanceM }).coerceAtLeast(0.0),
            avgHr = if (hrs.isEmpty()) 0 else hrs.sum() / hrs.size,
            ascentM = up,
            descentM = down,
        )
    }

    private fun climb(samples: List<Sample>): Pair<Double, Double> {
        var up = 0.0; var down = 0.0
        for (i in 1 until samples.size) {
            val d = samples[i].altitudeM - samples[i - 1].altitudeM
            if (d > 0) up += d else down -= d
        }
        return up to down
    }

    /** Scale 5, offset 500: −500 m to +12 606 m. A deck below its start stays positive. */
    private fun altitude(m: Double): Long = Math.round((m + 500.0) * 5).coerceIn(0, 0xFFFE)

    /** Null is "no reading", which Strava leaves blank; 0 would draw a flat line at zero. */
    private fun hr(bpm: Int): Long? = if (bpm <= 0) null else bpm.coerceAtMost(254).toLong()

    private fun speed(kph: Double): Long = speedMps(kph / 3.6)

    private fun speedMps(mps: Double): Long = Math.round(mps * 1000).coerceIn(0, 0xFFFE)
}
