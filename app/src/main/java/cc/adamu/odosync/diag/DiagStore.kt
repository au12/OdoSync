package cc.adamu.odosync.diag

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import cc.adamu.odosync.data.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Engine-health storage: one JSON file with the last [MAX_TRIPS] drive summaries, the CSV data
 * log of each drive, and the cached [DmeProfile]. Everything is written as soon as it changes,
 * so an app kill never loses a finished drive.
 */
class DiagStore private constructor(context: Context) {
    private val dir = File(context.filesDir, "diag").apply { mkdirs() }
    val logDir = File(dir, "logs").apply { mkdirs() }
    private val tripsFile = File(dir, "trips.json")
    private val prefs: SharedPreferences = context.getSharedPreferences("diag", Context.MODE_PRIVATE)

    private val _trips = MutableStateFlow(load())
    /** Oldest first. */
    val trips: StateFlow<List<TripSummary>> = _trips.asStateFlow()

    private fun load(): List<TripSummary> = runCatching {
        if (!tripsFile.exists()) return emptyList()
        val a = JSONArray(tripsFile.readText())
        (0 until a.length()).mapNotNull { runCatching { TripSummary.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }.getOrElse {
        EventLog.log("Engine health: couldn't read saved drives (${it.message})")
        emptyList()
    }

    @Synchronized
    private fun save(list: List<TripSummary>) {
        val trimmed = list.sortedBy { it.startedAt }.takeLast(MAX_TRIPS)
        val tmp = File(dir, "trips.json.tmp")
        tmp.writeText(JSONArray(trimmed.map { it.toJson() }).toString())
        tmp.renameTo(tripsFile)
        _trips.value = trimmed
    }

    @Synchronized
    fun upsert(trip: TripSummary) {
        save(_trips.value.filter { it.id != trip.id } + trip)
    }

    @Synchronized
    fun update(id: Long, block: (TripSummary) -> TripSummary) {
        val cur = _trips.value.firstOrNull { it.id == id } ?: return
        upsert(block(cur))
    }

    fun get(id: Long): TripSummary? = _trips.value.firstOrNull { it.id == id }

    fun logFile(name: String): File = File(logDir, name)

    /** Keep the newest logs within [MAX_LOG_FILES] files and [MAX_LOG_BYTES]. */
    fun pruneLogs() {
        val files = logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var bytes = 0L
        files.forEachIndexed { i, f ->
            bytes += f.length()
            if (i >= MAX_LOG_FILES || bytes > MAX_LOG_BYTES) f.delete()
        }
    }

    var profile: DmeProfile?
        get() = prefs.getString("profile", null)?.let { s -> runCatching { DmeProfile.fromJson(JSONObject(s)) }.getOrNull() }
        set(v) { prefs.edit { if (v == null) remove("profile") else putString("profile", v.toJson().toString()) } }

    /** Glovebox note ids, so later drives update the same daily/health notes. */
    fun noteId(key: String): Int? = prefs.getInt("note.$key", 0).takeIf { it > 0 }
    fun setNoteId(key: String, id: Int?) {
        prefs.edit { if (id == null) remove("note.$key") else putInt("note.$key", id) }
    }

    companion object {
        const val MAX_TRIPS = 300
        const val MAX_LOG_FILES = 80
        const val MAX_LOG_BYTES = 60L * 1024 * 1024

        @Volatile private var instance: DiagStore? = null
        fun get(context: Context): DiagStore = instance ?: synchronized(this) {
            instance ?: DiagStore(context.applicationContext).also { instance = it }
        }
    }
}
