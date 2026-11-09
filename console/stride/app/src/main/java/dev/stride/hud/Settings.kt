package dev.stride.hud

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything about *this* installation, rather than about treadmills.
 *
 * The console shipped with a household baked into it. Not as configuration
 * anyone had chosen to inline, but as the ordinary residue of building
 * something for one house: four names in an HTML array, a broker in
 * `local.properties`, kilometres everywhere, and — the one that actually
 * changed behaviour — a live coach gated on `walker == "Sam"`, so a second
 * person in the same house silently got a different product.
 *
 * Three rules this file exists to hold:
 *
 * * **A default must be a working treadmill.** Nothing here is required. With
 *   no broker set and no people added, the deck still runs and still counts.
 *   Home Assistant is an addition, never a
 *   dependency — so a fresh install is usable before it is configured.
 *
 * * **Preferences are not permissions.** Anything the *board* reports —
 *   incline range, speed range — is read from the machine and is not in here.
 *   Storing a max incline someone typed would be storing a way to ask the deck
 *   for something it cannot do.
 *
 * * **Per-person things belong to the person.** Publishing, weight and
 *   heart-rate zones are properties of a walker, not of the console, which is the whole fix for the
 *   name comparison.
 *
 * Stored in SharedPreferences because it is a handful of scalars and one small
 * list, read at start-up and on every settings save. A database would be a
 * database to maintain.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    companion object {
        // --- people ---
        const val PEOPLE = "people"
        const val DEFAULT_WALKER = "default_walker"
        const val ALLOW_GUEST = "allow_guest"

        /** Sentinel for "do not assume, ask on the welcome screen". */
        const val ASK = ""

        // --- units ---
        const val UNITS = "units"           // "km" | "mi"

        // --- home assistant ---
        const val HA_ENABLED = "ha_enabled"
        const val MQTT_HOST = "mqtt_host"
        const val MQTT_PORT = "mqtt_port"
        const val MQTT_USER = "mqtt_user"
        const val MQTT_PASS = "mqtt_pass"
        const val MQTT_TLS = "mqtt_tls"
        const val MQTT_PREFIX = "mqtt_prefix"

        // --- deck ---
        const val WARMUP_MIN = "warmup_min"
        const val COOLDOWN_MIN = "cooldown_min"
        const val WARMUP_KPH = "warmup_kph"
        const val INCLINE_RATE = "incline_rate"   // "gentle" | "normal" | "quick"
        const val OPEN_LAP_MIN = "open_lap_min"

        // --- heart rate ---
        const val HR_SOURCE = "hr_source"         // "auto" | "strap" | "grips"
        const val HR_ADDR = "hr_addr"
        const val HR_NAME = "hr_name"

        // --- headphones (fork-only; see Headphones.kt) ---
        const val HP_ADDR = "hp_addr"
        const val HP_NAME = "hp_name"

        // --- display ---
        const val SLEEP_MIN = "sleep_min"
        const val CLOCK_24 = "clock_24"
        const val BRIGHTNESS = "brightness"       // "dim" | "normal" | "bright"
        const val KEEP_AWAKE = "keep_awake"
        /** Package the switch button flips to, or "" for no button. */
        const val SWITCH_APP = "switch_app"

        // --- saved walks (fork-only) ---
        /** "barometric" | "generic": the device a .fit claims to come from. */
        const val FIT_IDENTITY = "fit_identity"
        /** Where NasCopy sends each .fit. The password is never one of these. */
        const val NAS_HOST = "nas_host"
        const val NAS_PORT = "nas_port"
        const val NAS_USER = "nas_user"
        /** Blank = the NAS account's home; relative = inside it; /absolute as is. */
        const val NAS_DIR = "nas_dir"

        /**
         * How fast a guided walk may move the deck, as three named presets
         * rather than two millisecond fields.
         *
         * `INCLINE_STEP` and `INCLINE_EVERY_MS` are a safety-shaped pair: they
         * are the reason the biggest jump in any template takes half a minute
         * to arrive rather than landing under someone mid-stride. Exposed as
         * raw numbers, somebody eventually sets five percent every five hundred
         * milliseconds. Named presets keep the reasoning attached.
         */
        /** What a typed maximum heart rate may be. Outside it, it is a typo. */
        val HR_MAX_RANGE = 120..230
        /** Zone floors as shares of maximum: the usual five-zone split. */
        val ZONE_SHARES = listOf(0.50, 0.60, 0.70, 0.80, 0.90)

        val INCLINE_RATES = mapOf(
            "gentle" to Pair(0.5, 4000L),
            "normal" to Pair(1.0, 3000L),
            "quick" to Pair(1.0, 1500L),
        )
    }

    // --- people ---------------------------------------------------------------

    /**
     * One walker.
     *
     * @param publish  do their walks leave the console for Home Assistant
     *
     * Publishing is closer to consent than to a preference, and it is the
     * reason a household member can use the treadmill without appearing on
     * somebody else's dashboard.
     *
     * There used to be a `coached` flag beside it, for a live coach in Home
     * Assistant. The coach is gone from this fork (2026-11-09: never wanted on
     * this treadmill), and an old saved list that still carries the key simply
     * has it ignored.
     */
    data class Person(
        val id: String,
        val name: String,
        val publish: Boolean,
        /** `person.jane_doe`, or empty for somebody who exists only here. */
        val haPerson: String = "",
        /**
         * Years, or 0 for "has not said" — which is the default and stays the
         * default. It buys one thing: a maximum heart rate via the usual
         * 220-minus-age, when [hrMaxSet] has not been given. That formula is
         * crude (±10-12 bpm between two people of the same age), which is why
         * a measured maximum, or zones from a lab or a watch, win over it.
         *
         * Per person, not per console: there is a person registry here because
         * more than one person walks on this machine, and one shared age would
         * be wrong for all but one of them.
         */
        val age: Int = 0,
        /**
         * Body weight in kg, or 0 for "has not said". Used for the power
         * estimate (see Power.kt) and nothing else; without it there is no
         * power figure rather than one worked out for an imaginary 70 kg.
         */
        val weightKg: Double = 0.0,
        /**
         * A maximum heart rate the walker knows, from a test or a watch, or 0
         * to fall back on 220-minus-age. See [maxPulse].
         */
        val hrMaxSet: Int = 0,
        /**
         * The bpm each of zones 1-5 starts at, ascending, when the walker has
         * set their own; empty to use the usual 50/60/70/80/90% of [maxPulse].
         * Five floors and no ceilings: zone 5 runs to whatever the heart does.
         */
        val zones: List<Int> = emptyList(),
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id).put("name", name)
            .put("publish", publish)
            .put("ha_person", haPerson)
            .put("age", age)
            .put("weight_kg", weightKg)
            .put("hr_max_set", hrMaxSet)
            .put("zones", JSONArray(zones))
            // Read-only, for the settings page: what the HUD will actually
            // use, so the page never has to repeat the defaulting rules.
            .put("hr_max", maxPulse)
            .put("zone_floors", JSONArray(zoneFloors()))

        /**
         * Maximum heart rate, or 0 if unknown.
         *
         * A measured one first. Otherwise clamped to an age this formula means
         * anything for: below about 13 and above about 100 it is
         * extrapolation, and a zone floor built on it would be a number the
         * console had made up.
         */
        val maxPulse: Int get() = when {
            hrMaxSet in HR_MAX_RANGE -> hrMaxSet
            age in 13..100 -> 220 - age
            else -> 0
        }

        /** The five zone floors in bpm, or empty when there are no zones. */
        fun zoneFloors(): List<Int> = when {
            zones.size == 5 -> zones
            maxPulse > 0 -> ZONE_SHARES.map { Math.round(it * maxPulse).toInt() }
            else -> emptyList()
        }

        /**
         * Which zone a pulse is in: 1-5, or 0 below zone 1 (standing about,
         * not training) and when there are no zones at all.
         */
        fun zoneOf(bpm: Int): Int {
            val f = zoneFloors()
            if (f.isEmpty() || bpm <= 0) return 0
            return f.indexOfLast { bpm >= it } + 1
        }
    }

    /**
     * A stable identity, fixed when somebody is added and never derived again.
     *
     * The id used to *be* the name — `slug(who)` at publish time — which meant
     * a rename silently orphaned every reading taken under the old spelling.
     * "Kez" becoming "Sam" started a second person's history and left the
     * first one frozen in Home Assistant looking like somebody who had stopped
     * walking.
     *
     * The name is now a label. The id is the thing, and it is deliberately
     * still the slug *at creation*, so existing devices — `stride_person_sam`
     * and `stride_person_alex`, with real history behind them — keep working
     * untouched rather than needing a migration.
     */
    private fun freshId(name: String, taken: Set<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .ifBlank { "walker" }
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    fun addPerson(name: String, haPerson: String = ""): Person? {
        val clean = name.trim().take(20)
        if (clean.isBlank()) return null
        val list = people().toMutableList()
        if (list.any { it.name.equals(clean, ignoreCase = true) }) return null
        val p = Person(
            id = freshId(clean, list.map { it.id }.toSet()),
            name = clean,
            // Somebody who is not in Home Assistant has nothing there to be
            // recorded against, so it starts off. It can be turned on — it
            // simply creates a device with no matching person, which is a
            // reasonable thing to want for a housemate with no HA account.
            publish = haPerson.isNotEmpty(),
            haPerson = haPerson,
        )
        list += p
        savePeople(list)
        return p
    }

    /**
     * Empty by default, and deliberately so.
     *
     * A console that ships with four strangers' names on the welcome screen is
     * worse than one that asks. When the list is empty the UI offers a single
     * guest walk and a way to add somebody.
     */
    fun people(): List<Person> {
        val raw = prefs.getString(PEOPLE, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val name = o.getString("name")
                Person(
                    // Older entries predate stable ids; fall back to the slug
                    // they were already being published under.
                    id = o.optString("id").ifBlank {
                        name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    },
                    name = name,
                    publish = o.optBoolean("publish", true),
                    haPerson = o.optString("ha_person"),
                    // Absent for everyone who existed before zones did, which
                    // is the same as declining to say — see Person.age.
                    age = o.optInt("age", 0),
                    weightKg = o.optDouble("weight_kg", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    hrMaxSet = o.optInt("hr_max_set", 0),
                    zones = o.optJSONArray("zones")?.let { z ->
                        (0 until z.length()).map { z.optInt(it) }
                    }?.takeIf { it.size == 5 } ?: emptyList(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePeople(list: List<Person>) {
        prefs.edit().putString(
            PEOPLE, JSONArray().apply { list.forEach { put(it.json()) } }.toString()
        ).apply()
        // A default that no longer names anybody would silently start every
        // walk as a guest. Drop it and let the welcome screen ask instead.
        if (list.none { it.name == defaultWalker() }) {
            prefs.edit().putString(DEFAULT_WALKER, ASK).apply()
        }
    }

    fun person(name: String): Person? = people().firstOrNull { it.name == name }
    fun personById(id: String): Person? = people().firstOrNull { it.id == id }

    /**
     * May this walker's walk be published.
     *
     * **Unknown walkers may not.** A guest is someone who has not agreed to
     * anything, so their walk stays on the console. That is the opposite of the
     * old behaviour, where an unrecognised name simply meant "not Sam" and
     * fell through to full publishing.
     */
    fun publishFor(name: String): Boolean = person(name)?.publish ?: false

    fun defaultWalker(): String = prefs.getString(DEFAULT_WALKER, ASK) ?: ASK
    fun allowGuest(): Boolean = prefs.getBoolean(ALLOW_GUEST, true)

    // --- units ----------------------------------------------------------------

    fun units(): String = prefs.getString(UNITS, "km") ?: "km"
    fun metric(): Boolean = units() == "km"

    // --- home assistant -------------------------------------------------------

    /**
     * Off until somebody sets a broker.
     *
     * The old build compiled credentials in from `local.properties`, so a clone
     * without those keys produced an app that could not connect — which the
     * comment there called "the right way to fail", and was, for one house. For
     * anyone else it is an app that ships someone else's password or does not
     * work. Runtime configuration is the only version of this that can be
     * released.
     */
    fun haEnabled(): Boolean = prefs.getBoolean(HA_ENABLED, false) && mqttHost().isNotBlank()

    fun mqttHost(): String = prefs.getString(MQTT_HOST, "") ?: ""
    fun mqttPort(): Int = prefs.getInt(MQTT_PORT, 1883)
    fun mqttUser(): String = prefs.getString(MQTT_USER, "") ?: ""
    fun mqttPass(): String = prefs.getString(MQTT_PASS, "") ?: ""
    fun mqttTls(): Boolean = prefs.getBoolean(MQTT_TLS, false)
    fun mqttPrefix(): String = (prefs.getString(MQTT_PREFIX, "stride") ?: "stride")
        .trim().trim('/').ifBlank { "stride" }

    fun brokerUri(): String =
        "${if (mqttTls()) "ssl" else "tcp"}://${mqttHost()}:${mqttPort()}"

    /**
     * First run only: adopt whatever was compiled in, then never look again.
     *
     * Moving the broker from `BuildConfig` to settings would otherwise take a
     * working console offline the moment it was updated — Home Assistant would
     * simply stop hearing from a treadmill that had been publishing happily for
     * a week, with nothing on screen to say why. That is a bad way to ship an
     * improvement.
     *
     * So `local.properties` becomes a *seed* rather than the source. A dev
     * build carries it in and this copies it to settings once, where it can
     * then be edited on the console like everything else. A public build has no
     * such keys, so `secret()` yields empty strings, nothing is seeded, and
     * Home Assistant stays off until somebody fills it in — which is the
     * behaviour that made the compiled-in version defensible in the first
     * place, now without shipping anyone's password.
     *
     * Keyed on a marker rather than on the fields being blank, so clearing the
     * broker on purpose is not undone at the next start-up.
     */
    fun seedFromBuildConfig() {
        if (prefs.getBoolean("seeded", false)) return
        prefs.edit().putBoolean("seeded", true).apply()

        val uri = BuildConfig.MQTT_BROKER
        if (uri.isBlank()) return

        // "tcp://host:1883" — the shape build.gradle.kts writes.
        val bare = uri.substringAfter("://")
        val host = bare.substringBefore(":")
        val port = bare.substringAfter(":", "1883").toIntOrNull() ?: 1883
        if (host.isBlank()) return

        prefs.edit()
            .putString(MQTT_HOST, host)
            .putInt(MQTT_PORT, port)
            .putString(MQTT_USER, BuildConfig.MQTT_USER)
            .putString(MQTT_PASS, BuildConfig.MQTT_PASS)
            .putBoolean(MQTT_TLS, uri.startsWith("ssl"))
            .putBoolean(HA_ENABLED, true)
            .apply()
    }

    // --- deck -----------------------------------------------------------------

    fun warmupMs(): Long = prefs.getInt(WARMUP_MIN, 2) * 60_000L
    fun cooldownMs(): Long = prefs.getInt(COOLDOWN_MIN, 2) * 60_000L
    fun warmupKph(): Double = prefs.getFloat(WARMUP_KPH, 2.0f).toDouble()
    fun openLapMin(): Int = prefs.getInt(OPEN_LAP_MIN, 20)

    fun inclineStep(): Double =
        INCLINE_RATES[prefs.getString(INCLINE_RATE, "normal")]?.first ?: 1.0

    fun inclineEveryMs(): Long =
        INCLINE_RATES[prefs.getString(INCLINE_RATE, "normal")]?.second ?: 3000L

    // --- heart rate -----------------------------------------------------------

    fun hrSource(): String = prefs.getString(HR_SOURCE, "auto") ?: "auto"
    fun hrAddr(): String = prefs.getString(HR_ADDR, "") ?: ""
    fun hrName(): String = prefs.getString(HR_NAME, "") ?: ""

    fun saveStrap(addr: String, name: String) {
        prefs.edit().putString(HR_ADDR, addr).putString(HR_NAME, name).apply()
    }

    fun forgetStrap() {
        prefs.edit().remove(HR_ADDR).remove(HR_NAME).apply()
    }

    // --- headphones -----------------------------------------------------------

    fun headphonesAddr(): String = prefs.getString(HP_ADDR, "") ?: ""
    fun headphonesName(): String = prefs.getString(HP_NAME, "") ?: ""

    fun saveHeadphones(addr: String, name: String) {
        prefs.edit().putString(HP_ADDR, addr).putString(HP_NAME, name).apply()
    }

    fun forgetHeadphones() {
        prefs.edit().remove(HP_ADDR).remove(HP_NAME).apply()
    }

    // --- display --------------------------------------------------------------

    fun sleepMs(): Long = prefs.getInt(SLEEP_MIN, 5) * 60_000L
    fun clock24(): Boolean = prefs.getBoolean(CLOCK_24, true)
    fun keepAwake(): Boolean = prefs.getBoolean(KEEP_AWAKE, true)

    /**
     * Off by default: a button floating over every app is something to ask
     * for, and it needs an overlay grant that a fresh console does not have.
     */
    fun switchApp(): String = prefs.getString(SWITCH_APP, "") ?: ""

    // --- saved walks (fork-only) ----------------------------------------------

    /**
     * On by default in this fork: without it, Strava swaps the treadmill's
     * climb for its own map's hills (see FitIdentity.BAROMETRIC).
     */
    fun fitIdentity(): String = prefs.getString(FIT_IDENTITY, "barometric") ?: "barometric"

    fun nasHost(): String = (prefs.getString(NAS_HOST, "") ?: "").trim()
    fun nasPort(): Int = prefs.getInt(NAS_PORT, 22).takeIf { it in 1..65535 } ?: 22
    fun nasUser(): String = (prefs.getString(NAS_USER, "") ?: "").trim()
    fun nasDir(): String = prefs.getString(NAS_DIR, "") ?: ""

    fun brightness(): Float = when (prefs.getString(BRIGHTNESS, "normal")) {
        "dim" -> 0.35f
        "bright" -> 1.0f
        else -> 0.7f
    }

    // --- the whole thing, for the settings screen ------------------------------

    /**
     * One object the settings UI renders from, so the screen cannot drift from
     * the store by holding its own idea of a default.
     */
    fun json(): JSONObject = JSONObject()
        .put("people", JSONArray().apply { people().forEach { put(it.json()) } })
        .put(DEFAULT_WALKER, defaultWalker())
        .put(ALLOW_GUEST, allowGuest())
        .put(UNITS, units())
        .put(HA_ENABLED, prefs.getBoolean(HA_ENABLED, false))
        .put(MQTT_HOST, mqttHost())
        .put(MQTT_PORT, mqttPort())
        .put(MQTT_USER, mqttUser())
        // Deliberately not the password.
        //
        // This object is handed to the settings page, and anything that can
        // read the page can read it — which on a debug build is anyone with
        // the DevTools socket. The screen only ever needed to know *whether*
        // a password is stored, so that is all it gets. The key is absent
        // rather than empty on purpose: a reader that wants the password now
        // gets `undefined` and fails loudly, where "" would quietly read as
        // "no password set". See field() in stride-settings.js for the other
        // half — an untouched box does not clear what it cannot see.
        .put("mqtt_pass_set", mqttPass().isNotEmpty())
        .put(MQTT_TLS, mqttTls())
        .put(MQTT_PREFIX, mqttPrefix())
        .put(WARMUP_MIN, prefs.getInt(WARMUP_MIN, 2))
        .put(COOLDOWN_MIN, prefs.getInt(COOLDOWN_MIN, 2))
        .put(WARMUP_KPH, prefs.getFloat(WARMUP_KPH, 2.0f).toDouble())
        .put(INCLINE_RATE, prefs.getString(INCLINE_RATE, "normal"))
        .put(OPEN_LAP_MIN, openLapMin())

        .put(HR_SOURCE, hrSource())
        .put(HR_ADDR, hrAddr())
        .put(HR_NAME, hrName())
        .put(HP_ADDR, headphonesAddr())
        .put(HP_NAME, headphonesName())
        .put(SLEEP_MIN, prefs.getInt(SLEEP_MIN, 5))
        .put(CLOCK_24, clock24())
        .put(BRIGHTNESS, prefs.getString(BRIGHTNESS, "normal"))
        .put(KEEP_AWAKE, keepAwake())
        .put(SWITCH_APP, switchApp())
        .put(FIT_IDENTITY, fitIdentity())
        .put(NAS_HOST, nasHost())
        .put(NAS_PORT, nasPort())
        .put(NAS_USER, nasUser())
        .put(NAS_DIR, nasDir())

    /**
     * Save one value, typed by what the key expects rather than by what
     * JavaScript happened to send. A WebView bridge hands everything over as a
     * string, and `getInt` on a key written as a String throws at read time —
     * a long way from the settings screen that caused it.
     */
    fun set(key: String, value: String) {
        val e = prefs.edit()
        when (key) {
            ALLOW_GUEST, HA_ENABLED, MQTT_TLS, CLOCK_24, KEEP_AWAKE ->
                e.putBoolean(key, value == "true" || value == "1")

            MQTT_PORT, NAS_PORT, WARMUP_MIN, COOLDOWN_MIN, OPEN_LAP_MIN, SLEEP_MIN ->
                e.putInt(key, value.toIntOrNull() ?: 0)

            WARMUP_KPH ->
                e.putFloat(key, value.toFloatOrNull() ?: 2.0f)

            else -> e.putString(key, value)
        }
        e.apply()
    }

    /** Settings only. People, totals and history are not touched. */
    fun resetSettings() {
        val keep = prefs.getString(PEOPLE, null)
        val ui = prefs.getString(MainActivity.PREF_UI, MainActivity.DEFAULT_UI)
        prefs.edit().clear()
            .putString(PEOPLE, keep)
            .putString(MainActivity.PREF_UI, ui)
            .apply()
    }
}
