package cc.adamu.odosync.upload

import android.util.Base64
import cc.adamu.odosync.data.AuthMode
import cc.adamu.odosync.data.SettingsSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny LubeLogger API client (https://docs.lubelogger.com/Advanced/API).
 *  - GET  /api/vehicles
 *  - GET  /api/vehicle/odometerrecords/latest?vehicleId=N   -> bare number
 *  - POST /api/vehicle/odometerrecords/add?vehicleId=N      JSON {date, odometer, notes, tags}
 * Auth: x-api-key header (an Editor-scoped key is enough) or HTTP Basic.
 * Note: the API stores the number as given; LubeLogger's per-vehicle odometer
 * multiplier/difference are UI-only, so unit conversion and offset happen in the app.
 */
class LubeLoggerClient(private val s: SettingsSnapshot) {

    data class Vehicle(val id: Int, val label: String)
    class HttpError(val code: Int, msg: String) : IOException(msg)

    private val base = s.baseUrl.trim().trimEnd('/')

    fun vehicles(): List<Vehicle> {
        val arr = JSONArray(request("GET", "/api/vehicles"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("id").toIntOrNull() ?: o.optInt("id")
            val label = listOf(o.optString("year"), o.optString("make"), o.optString("model"))
                .filter { it.isNotBlank() && it != "0" }.joinToString(" ")
                .ifBlank { "Vehicle $id" } +
                o.optString("licensePlate").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            Vehicle(id, label)
        }
    }

    fun latestOdometer(vehicleId: Int): Long? =
        request("GET", "/api/vehicle/odometerrecords/latest?vehicleId=$vehicleId")
            .trim().trim('"').toDoubleOrNull()?.toLong()

    fun addOdometer(vehicleId: Int, odometer: Long, date: Date = Date()): String {
        val body = JSONObject()
            .put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date))
            .put("odometer", odometer)
            .put("notes", s.notes)
            .put("tags", s.tag)
        val resp = request("POST", "/api/vehicle/odometerrecords/add?vehicleId=$vehicleId", body.toString())
        val json = runCatching { JSONObject(resp) }.getOrNull()
        if (json != null && json.has("success") && !json.optBoolean("success")) {
            throw IOException("LubeLogger refused: ${json.optString("message")}")
        }
        return json?.optString("message").orEmpty().ifBlank { "OK" }
    }

    private fun request(method: String, path: String, jsonBody: String? = null): String {
        require(base.startsWith("http")) { "LubeLogger URL must start with http:// or https://" }
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("culture-invariant", "true")
            when (s.authMode) {
                AuthMode.API_KEY -> conn.setRequestProperty("x-api-key", s.apiKey.trim())
                AuthMode.BASIC -> {
                    val token = Base64.encodeToString("${s.username}:${s.password}".toByteArray(), Base64.NO_WRAP)
                    conn.setRequestProperty("Authorization", "Basic $token")
                }
            }
            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val hint = when (code) {
                    401, 403 -> " (check the API key / credentials and that the key has Editor rights)"
                    404 -> " (check the URL and vehicle)"
                    else -> ""
                }
                throw HttpError(code, "HTTP $code$hint ${text.take(160)}")
            }
            // LubeLogger redirects unauthenticated requests to /Login with a 200 HTML page.
            if (text.trimStart().startsWith("<")) throw HttpError(401, "Got an HTML page - authentication failed")
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
