package cc.adamu.odosync.diag

import org.json.JSONArray
import org.json.JSONObject

enum class Severity { OK, WATCH, ALERT }

/** One finding about a drive, e.g. "Timing pulls creeping up". */
data class HealthAlert(
    val kind: String,
    val severity: Severity,
    val title: String,
    val message: String,
    val cylinder: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind).put("severity", severity.name).put("title", title).put("message", message)
        .apply { if (cylinder != null) put("cylinder", cylinder) }

    companion object {
        fun fromJson(o: JSONObject) = HealthAlert(
            o.getString("kind"), Severity.valueOf(o.getString("severity")), o.getString("title"),
            o.getString("message"), if (o.has("cylinder")) o.getInt("cylinder") else null,
        )
    }
}

/** A stretch where one or more cylinders ran noticeably less ignition advance than the rest. */
data class PullEvent(
    /** Seconds since the trip started. */
    val atS: Double,
    /** 1-based cylinder numbers involved. */
    val cylinders: List<Int>,
    val maxDeg: Double,
    val boostKpa: Double?,
    val iatC: Double?,
    val oilC: Double?,
    val speedKmh: Double?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("atS", atS).put("cylinders", JSONArray(cylinders)).put("maxDeg", maxDeg)
        .putOpt("boostKpa", boostKpa).putOpt("iatC", iatC).putOpt("oilC", oilC).putOpt("speedKmh", speedKmh)

    companion object {
        fun fromJson(o: JSONObject) = PullEvent(
            o.getDouble("atS"),
            o.getJSONArray("cylinders").let { a -> (0 until a.length()).map { a.getInt(it) } },
            o.getDouble("maxDeg"),
            o.optDoubleOrNull("boostKpa"), o.optDoubleOrNull("iatC"), o.optDoubleOrNull("oilC"), o.optDoubleOrNull("speedKmh"),
        )
    }
}

/**
 * Everything worth keeping about one drive. Pressures are kPa (boost as gauge, i.e. above
 * barometric), temperatures °C, distances km; the UI and uploads convert for display.
 */
data class TripSummary(
    val startedAt: Long,
    val endedAt: Long,
    val startKm: Long = -1,
    val endKm: Long = -1,
    /** Distance from the speed signal, used when the odometer delta isn't available. */
    val speedDistanceKm: Double = 0.0,

    // Boost
    val boostSeconds: Double = 0.0,
    val boostEpisodes: Int = 0,
    val peakBoostKpa: Double? = null,
    val peakTargetKpa: Double? = null,
    /** Mean of actual - target while the DME asked for real boost (>= 40 kPa) for over 1.5 s. */
    val meanBoostDevKpa: Double? = null,
    val worstUnderboostKpa: Double? = null,
    val worstOverboostKpa: Double? = null,
    val underboostEvents: Int = 0,
    val overboostEvents: Int = 0,

    // Timing
    val timingFramesInBoost: Int = 0,
    val pullEvents: Int = 0,
    val maxPullDeg: Double = 0.0,
    val maxPullCylinder: Int? = null,
    val perCylEvents: List<Int> = List(6) { 0 },
    val perCylMaxPull: List<Double> = List(6) { 0.0 },
    val meanTimingInBoost: Double? = null,
    /** 1-based cylinders whose timing channel was available and trusted on this drive. */
    val cylindersMonitored: List<Int> = emptyList(),
    val events: List<PullEvent> = emptyList(),

    // Temperatures
    val iatMaxC: Double? = null,
    val iatMaxInBoostC: Double? = null,
    /** Largest rise in intake temperature during a single boost episode. */
    val iatRiseMaxC: Double? = null,
    val oilMaxC: Double? = null,
    val oilHotSeconds: Double = 0.0,
    val coolantMaxC: Double? = null,
    /** Seconds in boost while the oil was still below 70 °C. */
    val coldBoostSeconds: Double = 0.0,

    // Fuel and mixture
    val railMinInBoostBar: Double? = null,
    val railMaxBar: Double? = null,
    val lowFuelMinInBoostBar: Double? = null,
    val lambdaMinInBoost: Double? = null,
    val lambdaMaxInBoost: Double? = null,

    /** Misfires counted by the DME during the drive, per counter (null = unknown). */
    val misfires: List<Int?> = List(6) { null },

    // Link statistics
    val polls: Int = 0,
    val pollErrors: Int = 0,
    val fastSeconds: Double = 0.0,

    // Filled in at the end of the drive
    val alerts: List<HealthAlert> = emptyList(),
    val logFile: String? = null,

    // Upload bookkeeping
    val gloveboxSynced: Boolean = false,
    val gloveboxLogLocation: String? = null,
    val homeAssistantSent: Boolean = false,
) {
    val id: Long get() = startedAt
    val durationS: Double get() = (endedAt - startedAt).coerceAtLeast(0) / 1000.0
    val distanceKm: Double get() = if (startKm > 0 && endKm >= startKm) (endKm - startKm).toDouble() else speedDistanceKm
    val boostMinutes: Double get() = boostSeconds / 60.0

    /** Pull events per minute spent in boost: the number the trend alerts watch. */
    val pullRate: Double? get() = if (boostSeconds >= MIN_BOOST_S_FOR_RATE) pullEvents / boostMinutes else null
    val misfireTotal: Int? get() = misfires.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
    val severity: Severity get() = alerts.maxOfOrNull { it.severity } ?: Severity.OK
    /** Enough time in boost for the drive to count toward trends. */
    val qualifies: Boolean get() = boostSeconds >= MIN_BOOST_S_FOR_RATE && timingFramesInBoost >= 5

    fun toJson(): JSONObject = JSONObject().apply {
        put("startedAt", startedAt); put("endedAt", endedAt); put("startKm", startKm); put("endKm", endKm)
        put("speedDistanceKm", speedDistanceKm)
        put("boostSeconds", boostSeconds); put("boostEpisodes", boostEpisodes)
        putOpt("peakBoostKpa", peakBoostKpa); putOpt("peakTargetKpa", peakTargetKpa)
        putOpt("meanBoostDevKpa", meanBoostDevKpa); putOpt("worstUnderboostKpa", worstUnderboostKpa)
        putOpt("worstOverboostKpa", worstOverboostKpa)
        put("underboostEvents", underboostEvents); put("overboostEvents", overboostEvents)
        put("timingFramesInBoost", timingFramesInBoost); put("pullEvents", pullEvents)
        put("maxPullDeg", maxPullDeg); putOpt("maxPullCylinder", maxPullCylinder)
        put("perCylEvents", JSONArray(perCylEvents)); put("perCylMaxPull", JSONArray(perCylMaxPull))
        putOpt("meanTimingInBoost", meanTimingInBoost)
        put("cylindersMonitored", JSONArray(cylindersMonitored))
        put("events", JSONArray(events.map { it.toJson() }))
        putOpt("iatMaxC", iatMaxC); putOpt("iatMaxInBoostC", iatMaxInBoostC); putOpt("iatRiseMaxC", iatRiseMaxC)
        putOpt("oilMaxC", oilMaxC); put("oilHotSeconds", oilHotSeconds); putOpt("coolantMaxC", coolantMaxC)
        put("coldBoostSeconds", coldBoostSeconds)
        putOpt("railMinInBoostBar", railMinInBoostBar); putOpt("railMaxBar", railMaxBar)
        putOpt("lowFuelMinInBoostBar", lowFuelMinInBoostBar)
        putOpt("lambdaMinInBoost", lambdaMinInBoost); putOpt("lambdaMaxInBoost", lambdaMaxInBoost)
        put("misfires", JSONArray(misfires.map { it ?: JSONObject.NULL }))
        put("polls", polls); put("pollErrors", pollErrors); put("fastSeconds", fastSeconds)
        put("alerts", JSONArray(alerts.map { it.toJson() }))
        putOpt("logFile", logFile)
        put("gloveboxSynced", gloveboxSynced); putOpt("gloveboxLogLocation", gloveboxLogLocation)
        put("homeAssistantSent", homeAssistantSent)
    }

    companion object {
        const val MIN_BOOST_S_FOR_RATE = 20.0

        fun fromJson(o: JSONObject): TripSummary = TripSummary(
            startedAt = o.getLong("startedAt"), endedAt = o.getLong("endedAt"),
            startKm = o.optLong("startKm", -1), endKm = o.optLong("endKm", -1),
            speedDistanceKm = o.optDouble("speedDistanceKm", 0.0),
            boostSeconds = o.optDouble("boostSeconds", 0.0), boostEpisodes = o.optInt("boostEpisodes"),
            peakBoostKpa = o.optDoubleOrNull("peakBoostKpa"), peakTargetKpa = o.optDoubleOrNull("peakTargetKpa"),
            meanBoostDevKpa = o.optDoubleOrNull("meanBoostDevKpa"),
            worstUnderboostKpa = o.optDoubleOrNull("worstUnderboostKpa"),
            worstOverboostKpa = o.optDoubleOrNull("worstOverboostKpa"),
            underboostEvents = o.optInt("underboostEvents"), overboostEvents = o.optInt("overboostEvents"),
            timingFramesInBoost = o.optInt("timingFramesInBoost"), pullEvents = o.optInt("pullEvents"),
            maxPullDeg = o.optDouble("maxPullDeg", 0.0),
            maxPullCylinder = if (o.has("maxPullCylinder")) o.getInt("maxPullCylinder") else null,
            perCylEvents = o.optJSONArray("perCylEvents")?.ints() ?: List(6) { 0 },
            perCylMaxPull = o.optJSONArray("perCylMaxPull")?.doubles() ?: List(6) { 0.0 },
            meanTimingInBoost = o.optDoubleOrNull("meanTimingInBoost"),
            cylindersMonitored = o.optJSONArray("cylindersMonitored")?.ints().orEmpty(),
            events = o.optJSONArray("events")?.let { a -> (0 until a.length()).map { PullEvent.fromJson(a.getJSONObject(it)) } }.orEmpty(),
            iatMaxC = o.optDoubleOrNull("iatMaxC"), iatMaxInBoostC = o.optDoubleOrNull("iatMaxInBoostC"),
            iatRiseMaxC = o.optDoubleOrNull("iatRiseMaxC"), oilMaxC = o.optDoubleOrNull("oilMaxC"),
            oilHotSeconds = o.optDouble("oilHotSeconds", 0.0), coolantMaxC = o.optDoubleOrNull("coolantMaxC"),
            coldBoostSeconds = o.optDouble("coldBoostSeconds", 0.0),
            railMinInBoostBar = o.optDoubleOrNull("railMinInBoostBar"), railMaxBar = o.optDoubleOrNull("railMaxBar"),
            lowFuelMinInBoostBar = o.optDoubleOrNull("lowFuelMinInBoostBar"),
            lambdaMinInBoost = o.optDoubleOrNull("lambdaMinInBoost"), lambdaMaxInBoost = o.optDoubleOrNull("lambdaMaxInBoost"),
            misfires = o.optJSONArray("misfires")?.let { a -> (0 until a.length()).map { if (a.isNull(it)) null else a.getInt(it) } }
                ?: List(6) { null },
            polls = o.optInt("polls"), pollErrors = o.optInt("pollErrors"), fastSeconds = o.optDouble("fastSeconds", 0.0),
            alerts = o.optJSONArray("alerts")?.let { a -> (0 until a.length()).map { HealthAlert.fromJson(a.getJSONObject(it)) } }.orEmpty(),
            logFile = o.optStringOrNull("logFile"),
            gloveboxSynced = o.optBoolean("gloveboxSynced"),
            gloveboxLogLocation = o.optStringOrNull("gloveboxLogLocation"),
            homeAssistantSent = o.optBoolean("homeAssistantSent"),
        )
    }
}

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

private fun JSONArray.ints(): List<Int> = (0 until length()).map { getInt(it) }
private fun JSONArray.doubles(): List<Double> = (0 until length()).map { getDouble(it) }
