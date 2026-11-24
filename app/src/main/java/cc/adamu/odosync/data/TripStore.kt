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
    /** Start time of the trip in progress (or the last one). */
    val tripStartAt: Long = 0,
    /** The last finished trip, shown in the dismissable summary notification. */
    val summary: TripSummary? = null,
)

enum class UploadOutcome { QUEUED, WAITING_WIFI, UPLOADED, ALREADY_THERE, BELOW_MIN, NOT_CONFIGURED, RETRYING, FAILED }

data class TripSummary(
    val startKm: Long,
    val endKm: Long,
    val startAt: Long,
    val endAt: Long,
    val upload: UploadOutcome,
    val uploadMessage: String = "",
) {
    val distanceKm: Long get() = (endKm - startKm).coerceAtLeast(0)
}

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
        tripStartAt = prefs.getLong("tripStartAt", 0),
        summary = if (prefs.getLong("sumEndAt", 0) <= 0) null else TripSummary(
            startKm = prefs.getLong("sumStartKm", -1),
            endKm = prefs.getLong("sumEndKm", -1),
            startAt = prefs.getLong("sumStartAt", 0),
            endAt = prefs.getLong("sumEndAt", 0),
            upload = runCatching { UploadOutcome.valueOf(prefs.getString("sumUpload", "")!!) }
                .getOrDefault(UploadOutcome.QUEUED),
            uploadMessage = prefs.getString("sumUploadMsg", "")!!,
        ),
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
            if (startKm != null) {
                putLong("lastTripStartKm", startKm)
                putLong("tripStartAt", System.currentTimeMillis())
            }
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

    @Synchronized
    fun saveSummary(sum: TripSummary) {
        prefs.edit()
            .putLong("sumStartKm", sum.startKm).putLong("sumEndKm", sum.endKm)
            .putLong("sumStartAt", sum.startAt).putLong("sumEndAt", sum.endAt)
            .putString("sumUpload", sum.upload.name).putString("sumUploadMsg", sum.uploadMessage)
            .commit()
        _flow.value = read()
    }

    /** Record an upload result against the last trip, if that trip is what was being uploaded. */
    @Synchronized
    fun setSummaryUpload(uploadedKm: Long, outcome: UploadOutcome, message: String = ""): Boolean {
        val sum = read().summary ?: return false
        val waiting = sum.upload in setOf(UploadOutcome.QUEUED, UploadOutcome.WAITING_WIFI, UploadOutcome.RETRYING)
        if (!waiting || uploadedKm < sum.endKm) return false
        saveSummary(sum.copy(upload = outcome, uploadMessage = message))
        return true
    }

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
