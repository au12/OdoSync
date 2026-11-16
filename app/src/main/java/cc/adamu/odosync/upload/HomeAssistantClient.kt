package cc.adamu.odosync.upload

import cc.adamu.odosync.diag.Severity
import cc.adamu.odosync.diag.TripSummary
import cc.adamu.odosync.diag.Units
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Home Assistant REST API (https://developers.home-assistant.io/docs/api/rest/) with a
 * long-lived access token:
 *  - POST /api/states/sensor.<prefix>_…   one sensor per headline number, updated after each drive
 *  - POST /api/events/odosync_drive_summary   every drive
 *  - POST /api/events/odosync_engine_alert    when a drive raised a Watch or Alert
 * No integration to install; an automation on the alert event sends the push notification.
 */
class HomeAssistantClient(baseUrl: String, private val token: String) {
    class HttpError(val code: Int, msg: String) : IOException(msg)

    private val base = baseUrl.trim().trimEnd('/')

    /** GET /api/ → "API running." when URL and token are right. */
    fun ping(): String = JSONObject(request("GET", "/api/", null)).optString("message", "OK")

    fun setState(entityId: String, state: String, attributes: JSONObject) {
        request("POST", "/api/states/$entityId", JSONObject().put("state", state).put("attributes", attributes).toString())
    }

    fun fireEvent(type: String, data: JSONObject) {
        request("POST", "/api/events/$type", data.toString())
    }

    private fun request(method: String, path: String, body: String?): String {
        require(base.startsWith("http")) { "Home Assistant URL must start with http:// or https://" }
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val hint = if (code == 401) " (check the long-lived access token)" else ""
                throw HttpError(code, "HTTP $code$hint ${text.take(120)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}

/** The sensors and events a finished drive turns into. Pure, so the unit tests can check it. */
object HaPayloads {
    data class State(val entityId: String, val state: String, val attributes: JSONObject)

    fun states(t: TripSummary, u: Units, prefix: String, vehicle: String): List<State> {
        val car = vehicle.ifBlank { "Car" }
        val ended = iso(t.endedAt)
        fun attrs(name: String, unit: String?, icon: String, deviceClass: String? = null) = JSONObject()
            .put("friendly_name", "$car $name")
            .put("icon", icon)
            .put("last_drive", ended)
            .apply {
                if (unit != null) put("unit_of_measurement", unit)
                if (deviceClass != null) put("device_class", deviceClass)
                if (unit != null) put("state_class", "measurement")
            }
        fun p(kPa: Double?) = kPa?.let { fmt(u.pressure(it), if (u == Units.US) 1 else 2) } ?: "unknown"
        fun c(v: Double?) = v?.let { u.temp(it).roundToInt().toString() } ?: "unknown"

        val out = mutableListOf<State>()
        out += State("sensor.${prefix}_engine_health", t.severity.name.lowercase(),
            attrs("engine health", null, "mdi:engine")
                .put("alerts", JSONArray(t.alerts.map { it.title }))
                .put("messages", JSONArray(t.alerts.map { it.message }))
                .put("distance", fmt(u.distance(t.distanceKm), 1) + " " + u.distanceUnit)
                .put("boost_minutes", fmt(t.boostMinutes, 1)))
        out += State("sensor.${prefix}_timing_pull_max", fmt(t.maxPullDeg, 1),
            attrs("max timing pull (last drive)", "°", "mdi:sine-wave")
                .put("cylinder", t.maxPullCylinder ?: JSONObject.NULL)
                .put("pull_events", t.pullEvents)
                .put("per_cylinder_events", JSONArray(t.perCylEvents))
                .put("cylinders_monitored", JSONArray(t.cylindersMonitored)))
        out += State("sensor.${prefix}_timing_pull_rate", t.pullRate?.let { fmt(it, 2) } ?: "unknown",
            attrs("timing pulls per boost minute", "/min", "mdi:chart-line-variant")
                .put("boost_minutes", fmt(t.boostMinutes, 1)))
        out += State("sensor.${prefix}_boost_peak", p(t.peakBoostKpa),
            attrs("peak boost (last drive)", u.pressureUnit, "mdi:gauge", "pressure")
                .put("target_peak", p(t.peakTargetKpa)))
        out += State("sensor.${prefix}_boost_vs_target", p(t.meanBoostDevKpa),
            attrs("boost vs target (avg)", u.pressureUnit, "mdi:gauge-low", "pressure")
                .put("worst_under", p(t.worstUnderboostKpa))
                .put("worst_over", p(t.worstOverboostKpa))
                .put("underboost_events", t.underboostEvents)
                .put("overboost_events", t.overboostEvents))
        out += State("sensor.${prefix}_iat_peak", c(t.iatMaxC),
            attrs("peak intake air temp", u.tempUnit, "mdi:thermometer", "temperature")
                .put("peak_in_boost", c(t.iatMaxInBoostC))
                .put("max_rise_in_run", t.iatRiseMaxC?.let { (if (u == Units.US) it * 9 / 5 else it).roundToInt() } ?: JSONObject.NULL))
        out += State("sensor.${prefix}_oil_temp_peak", c(t.oilMaxC),
            attrs("peak oil temp", u.tempUnit, "mdi:oil-temperature", "temperature"))
        out += State("sensor.${prefix}_coolant_temp_peak", c(t.coolantMaxC),
            attrs("peak coolant temp", u.tempUnit, "mdi:coolant-temperature", "temperature"))
        out += State("sensor.${prefix}_misfires", t.misfireTotal?.toString() ?: "unknown",
            attrs("misfires (last drive)", null, "mdi:engine-off-outline")
                .put("per_counter", JSONArray(t.misfires.map { it ?: JSONObject.NULL })))
        return out
    }

    fun summaryEvent(t: TripSummary, u: Units, vehicle: String): JSONObject = JSONObject()
        .put("vehicle", vehicle)
        .put("started", iso(t.startedAt)).put("ended", iso(t.endedAt))
        .put("severity", t.severity.name.lowercase())
        .put("distance", fmt(u.distance(t.distanceKm), 1)).put("distance_unit", u.distanceUnit)
        .put("boost_minutes", fmt(t.boostMinutes, 1))
        .put("pull_events", t.pullEvents)
        .put("pull_rate", t.pullRate ?: JSONObject.NULL)
        .put("max_pull_deg", fmt(t.maxPullDeg, 1))
        .put("max_pull_cylinder", t.maxPullCylinder ?: JSONObject.NULL)
        .put("peak_boost", t.peakBoostKpa?.let { fmt(u.pressure(it), 2) } ?: JSONObject.NULL)
        .put("pressure_unit", u.pressureUnit)
        .put("iat_peak", t.iatMaxC?.let { u.temp(it).roundToInt() } ?: JSONObject.NULL)
        .put("oil_peak", t.oilMaxC?.let { u.temp(it).roundToInt() } ?: JSONObject.NULL)
        .put("temp_unit", u.tempUnit)
        .put("alerts", JSONArray(t.alerts.map { it.toJson() }))

    /** null when the drive raised nothing worth a push. */
    fun alertEvent(t: TripSummary, vehicle: String): JSONObject? {
        if (t.severity == Severity.OK) return null
        val sorted = t.alerts.sortedByDescending { it.severity }
        val top = sorted.first()
        return JSONObject()
            .put("vehicle", vehicle)
            .put("severity", t.severity.name.lowercase())
            .put("title", if (sorted.size == 1) top.title else "${top.title} (+${sorted.size - 1} more)")
            .put("message", sorted.joinToString("\n") { "${it.title}: ${it.message}" })
            .put("alerts", JSONArray(sorted.map { it.toJson() }))
            .put("ended", iso(t.endedAt))
    }

    private fun fmt(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)

    private fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        .apply { timeZone = TimeZone.getDefault() }.format(Date(t))
}
