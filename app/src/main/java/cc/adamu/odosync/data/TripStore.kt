package cc.adamu.odosync.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Durable odometer/upload bookkeeping. Survives process death so no reading is ever lost. */
data class TripState(
    val lastKm: Long = -1,
    val lastKmAt: Long = 0,
    val lastSource: String = "",
    /** Newest reading (km) that has not been confirmed by LubeLogger yet; -1 = nothing pending. */
    val pendingKm: Long = -1,
    /** Last value LubeLogger accepted, already in upload units (offset applied). */
    val lastUploadedValue: Long = -1,
    val lastUploadedAt: Long = 0,
    val lastUploadError: String = "",
    val lastTripStartKm: Long = -1,
    val lastTripEndKm: Long = -1,
    val lastTripEndAt: Long = 0,
)

class TripStore private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("trip", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<TripState> = _flow.asStateFlow()
    val current: TripState get() = _flow.value

    private fun read() = TripState(
        lastKm = prefs.getLong("lastKm", -1),
        lastKmAt = prefs.getLong("lastKmAt", 0),
        lastSource = prefs.getString("lastSource", "")!!,
        pendingKm = prefs.getLong("pendingKm", -1),
        lastUploadedValue = prefs.getLong("lastUploadedValue", -1),
        lastUploadedAt = prefs.getLong("lastUploadedAt", 0),
        lastUploadError = prefs.getString("lastUploadError", "")!!,
        lastTripStartKm = prefs.getLong("lastTripStartKm", -1),
        lastTripEndKm = prefs.getLong("lastTripEndKm", -1),
        lastTripEndAt = prefs.getLong("lastTripEndAt", 0),
    )

    @Synchronized
    fun recordReading(km: Long, source: String) {
        prefs.edit()
            .putLong("lastKm", km)
            .putLong("lastKmAt", System.currentTimeMillis())
            .putString("lastSource", source)
            .putLong("pendingKm", km)
            .commit()
        _flow.value = read()
    }

    @Synchronized
    fun markTrip(startKm: Long?, endKm: Long?) {
        prefs.edit().apply {
            if (startKm != null) putLong("lastTripStartKm", startKm)
            if (endKm != null) {
                putLong("lastTripEndKm", endKm)
                putLong("lastTripEndAt", System.currentTimeMillis())
            }
        }.commit()
        _flow.value = read()
    }

    /** Called after LubeLogger confirmed [value]; clears pending only if no newer reading arrived. */
    @Synchronized
    fun markUploaded(km: Long, value: Long) {
        val e = prefs.edit()
            .putLong("lastUploadedValue", value)
            .putLong("lastUploadedAt", System.currentTimeMillis())
            .putString("lastUploadError", "")
        if (prefs.getLong("pendingKm", -1) <= km) e.putLong("pendingKm", -1)
        e.commit()
        _flow.value = read()
    }

    @Synchronized
    fun clearPendingIfAtMost(km: Long) {
        if (prefs.getLong("pendingKm", -1) <= km) prefs.edit().putLong("pendingKm", -1).commit()
        _flow.value = read()
    }

    fun setLastIdleStop(t: Long) { prefs.edit().putLong("lastIdleStop", t).apply() }
    fun lastIdleStop(): Long = prefs.getLong("lastIdleStop", 0)

    /** Units/offset/vehicle changed: forget the old baseline so the next reading is uploaded. */
    @Synchronized
    fun resetUploadBaseline() {
        prefs.edit().putLong("lastUploadedValue", -1).putString("lastUploadError", "").commit()
        _flow.value = read()
    }

    @Synchronized
    fun setUploadError(msg: String) {
        prefs.edit().putString("lastUploadError", msg).commit()
        _flow.value = read()
    }

    companion object {
        @Volatile private var instance: TripStore? = null
        fun get(context: Context): TripStore = instance ?: synchronized(this) {
            instance ?: TripStore(context.applicationContext).also { instance = it }
        }
    }
}
