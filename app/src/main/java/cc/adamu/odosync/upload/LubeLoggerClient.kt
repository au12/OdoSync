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
 * Tiny LubeLogger API client (https://docs.lubelogger.com/Advanced/API). Glovebox (au12/glovebox)
 * serves the same API.
 *  - GET  /api/vehicles
 *  - GET  /api/vehicle/odometerrecords/latest?vehicleId=N   -> bare number
 *  - POST /api/vehicle/odometerrecords/add?vehicleId=N      JSON {date, odometer, notes, tags}
 *  - POST /api/vehicle/notes/add?vehicleId=N, PUT /api/vehicle/notes/update   (drive summaries)
 *  - POST /api/documents/upload                              (drive CSV logs, multipart)
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

    /** A vehicle note (Glovebox renders NoteText as markdown). [id] = null creates one. */
    data class NoteBody(
        val description: String,
        val text: String,
        val pinned: Boolean = false,
        val tags: String = "",
        val files: List<UploadedFile> = emptyList(),
    )

    data class UploadedFile(val name: String, val location: String)

    /** POST /api/vehicle/notes/add → the new note's id. */
    fun addNote(vehicleId: Int, note: NoteBody): Int {
        val resp = request("POST", "/api/vehicle/notes/add?vehicleId=$vehicleId", noteJson(note, null).toString())
        val json = runCatching { JSONObject(resp) }.getOrNull() ?: throw IOException("LubeLogger: unexpected reply '${resp.take(80)}'")
        if (!json.optBoolean("success", true)) throw IOException("LubeLogger refused the note: ${json.optString("message")}")
        val id = json.optJSONObject("additionalData")?.optInt("recordId", 0) ?: 0
        if (id <= 0) throw IOException("LubeLogger didn't return the note id")
        return id
    }

    /**
     * PUT /api/vehicle/notes/update. Returns false when the note no longer exists (deleted in
     * the web UI), so the caller can create a fresh one.
     */
    fun updateNote(id: Int, note: NoteBody): Boolean {
        return try {
            val resp = request("PUT", "/api/vehicle/notes/update", noteJson(note, id).toString())
            val json = runCatching { JSONObject(resp) }.getOrNull()
            json == null || json.optBoolean("success", true)
        } catch (e: HttpError) {
            if (e.code == 400 && e.message?.contains("Invalid Record Id", ignoreCase = true) == true) false else throw e
        }
    }

    private fun noteJson(n: NoteBody, id: Int?): JSONObject = JSONObject().apply {
        if (id != null) put("id", id.toString())
        put("description", n.description)
        put("noteText", n.text)
        put("pinned", n.pinned.toString())
        put("tags", n.tags)
        put("files", JSONArray(n.files.map { JSONObject().put("name", it.name).put("location", it.location) }))
    }

    /** POST /api/documents/upload (multipart) → where LubeLogger stored the file. */
    fun uploadDocument(name: String, mime: String, bytes: ByteArray): UploadedFile {
        val boundary = "----OdoSync" + System.nanoTime()
        val body = java.io.ByteArrayOutputStream().apply {
            write("--$boundary\r\n".toByteArray())
            write("Content-Disposition: form-data; name=\"documents\"; filename=\"$name\"\r\n".toByteArray())
            write("Content-Type: $mime\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val resp = request("POST", "/api/documents/upload", body = body, contentType = "multipart/form-data; boundary=$boundary")
        val arr = runCatching { JSONArray(resp) }.getOrNull() ?: throw IOException("LubeLogger upload: unexpected reply '${resp.take(80)}'")
        val o = arr.optJSONObject(0) ?: throw IOException("LubeLogger upload: no file in reply")
        return UploadedFile(o.optString("name", name), o.getString("location"))
    }

    private fun request(method: String, path: String, jsonBody: String? = null): String =
        request(method, path, jsonBody?.toByteArray(), if (jsonBody != null) "application/json" else null)

    private fun request(method: String, path: String, body: ByteArray?, contentType: String?): String {
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
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
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
