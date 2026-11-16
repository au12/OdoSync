package cc.adamu.odosync.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.data.NoteMode
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.data.SettingsSnapshot
import cc.adamu.odosync.diag.DiagStore
import cc.adamu.odosync.diag.TripSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * After a drive: sends the engine-health summary to Home Assistant and writes it to
 * Glovebox/LubeLogger as notes (plus the CSV data log as an attachment). Each drive remembers
 * what has gone where, so a retry only redoes what failed, and drives that piled up while
 * offline go out in order.
 */
class DiagUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = Settings.get(applicationContext).current
        val store = DiagStore.get(applicationContext)
        var failed = false

        if (s.homeAssistantConfigured) {
            val pending = store.trips.value.filter { !it.homeAssistantSent }.takeLast(MAX_BACKLOG)
            if (pending.isNotEmpty()) {
                val ha = HomeAssistantClient(s.haUrl, s.haToken)
                for (t in pending) {
                    try {
                        sendToHomeAssistant(ha, t, s)
                        store.update(t.id) { it.copy(homeAssistantSent = true) }
                    } catch (e: Exception) {
                        EventLog.log("Home Assistant: ${e.message ?: e.javaClass.simpleName}")
                        failed = true
                        break
                    }
                }
                if (!failed) EventLog.log("Home Assistant: sent ${pending.size} drive${if (pending.size == 1) "" else "s"}")
            }
        }

        if (s.lubeLoggerConfigured && s.notesEnabled) {
            try {
                syncNotes(store, s)
            } catch (e: Exception) {
                EventLog.log("Drive notes: ${e.message ?: e.javaClass.simpleName}")
                failed = true
            }
        }
        if (failed && runAttemptCount < 8) Result.retry() else Result.success()
    }

    private fun sendToHomeAssistant(ha: HomeAssistantClient, t: TripSummary, s: SettingsSnapshot) {
        val vehicle = s.vehicleName
        for (st in HaPayloads.states(t, s.healthUnits, s.haEntityPrefix, vehicle)) ha.setState(st.entityId, st.state, st.attributes)
        ha.fireEvent("odosync_drive_summary", HaPayloads.summaryEvent(t, s.healthUnits, vehicle))
        HaPayloads.alertEvent(t, vehicle)?.let { ha.fireEvent("odosync_engine_alert", it) }
    }

    private fun syncNotes(store: DiagStore, s: SettingsSnapshot) {
        val pending = store.trips.value.filter { !it.gloveboxSynced }.takeLast(MAX_BACKLOG)
        if (pending.isEmpty()) return
        val client = LubeLoggerClient(s)
        val r = NoteRenderer(s.healthUnits)

        // 1. Data logs first, so the notes can link them.
        if (s.attachLogs) {
            for (t in pending) {
                val name = t.logFile ?: continue
                if (t.gloveboxLogLocation != null) continue
                val f = store.logFile(name)
                if (!f.exists() || f.length() == 0L) continue
                val up = client.uploadDocument(name, "text/csv", f.readBytes())
                store.update(t.id) { it.copy(gloveboxLogLocation = up.location) }
            }
        }
        fun files(trips: List<TripSummary>) = trips.mapNotNull { t ->
            val loc = store.get(t.id)?.gloveboxLogLocation ?: t.gloveboxLogLocation
            loc?.let { LubeLoggerClient.UploadedFile(t.logFile ?: "drive.csv", it) }
        }

        // 2. Drive notes.
        when (s.noteMode) {
            NoteMode.DAILY -> {
                for ((day, _) in pending.groupBy { r.dayKey(it.startedAt) }) {
                    val all = store.trips.value.filter { r.dayKey(it.startedAt) == day }
                    val body = LubeLoggerClient.NoteBody(
                        description = r.dailyDescription(all.first().startedAt),
                        text = r.daily(all),
                        tags = "odosync drivelog",
                        files = files(all),
                    )
                    upsert(client, store, s.vehicleId, "day:$day", body)
                    all.forEach { t -> store.update(t.id) { it.copy(gloveboxSynced = true) } }
                }
            }
            NoteMode.PER_TRIP -> {
                for (t in pending) {
                    val cur = store.get(t.id) ?: t
                    val body = LubeLoggerClient.NoteBody(
                        description = r.tripDescription(cur), text = r.trip(cur),
                        tags = "odosync drivelog", files = files(listOf(cur)),
                    )
                    upsert(client, store, s.vehicleId, "trip:${t.id}", body)
                    store.update(t.id) { it.copy(gloveboxSynced = true) }
                }
            }
        }

        // 3. The pinned overview.
        if (s.healthNote) {
            val body = LubeLoggerClient.NoteBody(
                description = r.healthDescription(s.vehicleName),
                text = r.health(store.trips.value, System.currentTimeMillis()),
                pinned = true, tags = "odosync",
            )
            upsert(client, store, s.vehicleId, "health:${s.vehicleId}", body)
        }
        EventLog.log("Drive notes: updated in LubeLogger (${pending.size} drive${if (pending.size == 1) "" else "s"})")
    }

    private fun upsert(client: LubeLoggerClient, store: DiagStore, vehicleId: Int, key: String, body: LubeLoggerClient.NoteBody) {
        val id = store.noteId(key)
        if (id != null && client.updateNote(id, body)) return
        store.setNoteId(key, client.addNote(vehicleId, body))
    }

    companion object {
        private const val UNIQUE = "diag-upload"
        private const val MAX_BACKLOG = 50

        fun enqueue(context: Context, reason: String) {
            val s = Settings.get(context).current
            if (!s.homeAssistantConfigured && !(s.lubeLoggerConfigured && s.notesEnabled)) return
            val req = OneTimeWorkRequestBuilder<DiagUploadWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            EventLog.log("Engine health upload queued ($reason)")
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
