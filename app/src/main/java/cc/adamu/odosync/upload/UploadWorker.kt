package cc.adamu.odosync.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.data.LiveStatus
import cc.adamu.odosync.data.TripPhase
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.data.TripStore
import cc.adamu.odosync.data.UploadOutcome
import cc.adamu.odosync.service.TripNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Pushes the pending odometer reading to LubeLogger. Runs after a trip ends, only when a
 * network is available, retrying with backoff - so nothing ever happens on the network while
 * Android Auto is in use unless a trip end coincides with it.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = Settings.get(applicationContext).current
        val store = TripStore.get(applicationContext)
        val state = store.current
        if (state.pendingKm <= 0) return@withContext Result.success()
        // The safety-net run never uploads mid-drive; the trip-end upload covers that.
        if (tags.contains(TAG_PERIODIC) && LiveStatus.current.phase == TripPhase.ACTIVE) {
            return@withContext Result.success()
        }
        val km = state.pendingKm
        if (!settings.lubeLoggerConfigured) {
            store.setUploadError("LubeLogger not configured")
            report(km, UploadOutcome.NOT_CONFIGURED)
            return@withContext Result.success()
        }
        val value = settings.toUploadValue(km)
        if (state.lastUploadedValue >= 0 && value < state.lastUploadedValue + settings.minDelta) {
            store.clearPendingIfAtMost(km)
            report(km, UploadOutcome.BELOW_MIN)
            return@withContext Result.success()
        }
        val client = LubeLoggerClient(settings)
        try {
            // Never create a record lower than what LubeLogger already knows (e.g. you logged a
            // fill-up by hand with a higher reading).
            val latest = runCatching { client.latestOdometer(settings.vehicleId) }.getOrNull()
            if (latest != null && latest >= value) {
                EventLog.log("Upload skipped: LubeLogger already at $latest ≥ $value")
                store.markUploaded(km, latest)
                report(km, UploadOutcome.ALREADY_THERE)
                return@withContext Result.success()
            }
            val msg = client.addOdometer(settings.vehicleId, value)
            EventLog.log("Uploaded odometer $value ${settings.uploadUnit.label} to LubeLogger ($msg)")
            store.markUploaded(km, value)
            report(km, UploadOutcome.UPLOADED)
            Result.success()
        } catch (e: LubeLoggerClient.HttpError) {
            EventLog.log("Upload failed: ${e.message}")
            store.setUploadError(e.message ?: "HTTP ${e.code}")
            val permanent = e.code in 400..499 && e.code != 408 && e.code != 429
            report(km, if (permanent) UploadOutcome.FAILED else UploadOutcome.RETRYING, shortReason(e))
            if (permanent) Result.failure() else Result.retry()
        } catch (e: Exception) {
            EventLog.log("Upload failed: ${e.javaClass.simpleName}: ${e.message}")
            store.setUploadError(e.message ?: e.javaClass.simpleName)
            report(km, UploadOutcome.RETRYING, shortReason(e))
            Result.retry()
        }
    }

    /** Update the trip-summary notification if this upload was for the trip it shows. */
    private fun report(km: Long, outcome: UploadOutcome, message: String = "") {
        if (TripStore.get(applicationContext).setSummaryUpload(km, outcome, message)) {
            TripNotifier.show(applicationContext, onlyIfShowing = true)
        }
    }

    private fun shortReason(e: Exception): String = when (e) {
        is LubeLoggerClient.HttpError -> when (e.code) {
            401, 403 -> "HTTP ${e.code} (check the API key)"
            else -> "HTTP ${e.code}"
        }
        is java.net.UnknownHostException -> "server not reachable"
        is java.net.SocketTimeoutException -> "timed out"
        is java.net.ConnectException -> "connection refused"
        else -> e.message?.take(60) ?: e.javaClass.simpleName
    }

    companion object {
        private const val UNIQUE = "odometer-upload"
        private const val PERIODIC = "odometer-upload-safety"
        private const val TAG_PERIODIC = "periodic"

        fun enqueue(context: Context, reason: String) {
            val s = Settings.get(context).current
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            EventLog.log("Upload queued ($reason)")
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
        }

        /** Safety net: if the app was killed mid-trip, flush anything pending a few times a day. */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<UploadWorker>(6, TimeUnit.HOURS)
                .addTag(TAG_PERIODIC)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
