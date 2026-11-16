package cc.adamu.odosync.diag

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cc.adamu.odosync.OdoSyncApp
import cc.adamu.odosync.R
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.link.ObdLink
import cc.adamu.odosync.obd.CarReader
import cc.adamu.odosync.ui.MainActivity
import cc.adamu.odosync.upload.DiagUploadWorker
import kotlinx.coroutines.channels.ReceiveChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Engine-health monitoring for one drive: learns the DME (first drive only), samples it between
 * the trip service's housekeeping ticks, and at the end stores the summary, judges it against
 * earlier drives, queues the Glovebox/Home Assistant uploads and raises a notification if
 * something needs a look. Lives exactly as long as the trip.
 */
class DiagSession(
    private val context: Context,
    private val link: ObdLink,
    reader: CarReader,
    private val startKm: Long,
    private val t0: Long = System.currentTimeMillis(),
) {
    private val store = DiagStore.get(context)
    private val settings get() = Settings.get(context).current
    private val dme = DmeReader(reader)
    private var sampler: DiagSampler? = null
    private var log: TripCsvLog? = null
    private var discoveryTried = false

    /** Sampling fast (under boost): the trip service holds off its odometer read meanwhile. */
    val busy: Boolean get() = sampler?.fast == true

    init {
        LiveDiag.update { LiveHealth(active = true, learning = true, note = "Connecting to the engine computer…") }
    }

    /** Sample for up to [ms]; returns true if [wake] fired. */
    suspend fun runFor(ms: Long, wake: ReceiveChannel<Unit>): Boolean {
        val s = sampler ?: start() ?: return waitOut(ms, wake)
        return s.runFor(ms, wake)
    }

    private suspend fun waitOut(ms: Long, wake: ReceiveChannel<Unit>): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(ms) { wake.receive() } != null

    private suspend fun start(): DiagSampler? {
        if (discoveryTried) return null
        var profile = store.profile?.takeIf { System.currentTimeMillis() - it.discoveredAt < PROFILE_MAX_AGE_MS }
        if (profile == null) {
            discoveryTried = true
            LiveDiag.update { it.copy(learning = true, note = "Learning what this DME reports (first drive only)…") }
            EventLog.log("Engine health: learning what the DME reports")
            val learnt = dme.discover(System.currentTimeMillis())
            if (learnt == null) {
                LiveDiag.update { it.copy(learning = false, note = "The engine computer isn't answering health requests") }
                return null
            }
            // Keep what earlier drives learnt about the cylinder 5/6 channels.
            profile = learnt.copy(channelVerdicts = store.profile?.channelVerdicts.orEmpty())
            store.profile = profile
        }
        discoveryTried = true
        val s = settings
        val name = "drive-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(t0)) + ".csv"
        val csv = TripCsvLog(store.logFile(name), t0).also { runCatching { it.open() } }
        log = csv
        val analyzer = TripAnalyzer(t0, s.thresholds, profile.channelVerdicts, startKm)
        EventLog.log("Engine health: monitoring ${profile.chosen.size} DME values" +
            if (s.healthFastSampling) " (fast sampling under boost)" else "")
        return DiagSampler(dme, link, profile, analyzer, csv, s.healthFastSampling).also { sampler = it }
    }

    /** Best-effort last reads (misfire counters) while the DME is still awake. */
    suspend fun finalReads() {
        runCatching { sampler?.finalReads() }
    }

    /** Close the drive out. Safe to call from a non-suspending path (service shutdown). */
    fun finish(endKm: Long): TripSummary? {
        link.setFastMode(false)
        val sm = sampler
        val csv = log
        csv?.close()
        LiveDiag.reset()
        if (sm == null) {
            csv?.file?.delete()
            return null
        }
        val now = System.currentTimeMillis()
        var summary = sm.analyzer.finish(now, endKm, logFile = csv?.takeIf { it.rowCount > 0 }?.file?.name)
        if (summary.polls < MIN_POLLS && summary.durationS < 60) {
            csv?.file?.delete()
            EventLog.log("Engine health: drive too short to keep")
            return null
        }
        val s = settings
        summary = summary.copy(alerts = TrendAnalyzer(s.thresholds, s.healthUnits).evaluate(summary, store.trips.value))
        store.upsert(summary)
        store.pruneLogs()

        // Remember what this drive settled about the inferred cylinder 5/6 channels, and
        // re-learn the DME next time if most requests failed (different DME software?).
        val verdicts = sm.analyzer.channelVerdicts()
        val profile = sm.profile
        val newVerdicts = verdicts.filterKeys { it !in profile.channelVerdicts }
        newVerdicts.forEach { (sig, ok) ->
            EventLog.log("Engine health: ${sig.label} " + if (ok) "tracks cylinders 1-4; now used for pull detection"
                else "(DID %04X) doesn't behave like ignition timing; ignoring it".format(profile.chosen[sig] ?: 0))
        }
        store.profile = if (summary.polls >= 20 && summary.pollErrors * 2 > summary.polls) {
            EventLog.log("Engine health: most DME requests failed; will re-learn the DME next drive")
            null
        } else profile.copy(channelVerdicts = profile.channelVerdicts + verdicts)

        EventLog.log("Engine health: drive saved: ${describe(summary, s.healthUnits)}")
        summary.alerts.forEach { EventLog.log("Engine health ${it.severity.name.lowercase()}: ${it.title}") }
        if (summary.severity != Severity.OK) notify(summary)
        DiagUploadWorker.enqueue(context, "drive end")
        return summary
    }

    @SuppressLint("MissingPermission")
    private fun notify(t: TripSummary) {
        val top = t.alerts.sortedByDescending { it.severity }.first()
        val open = PendingIntent.getActivity(context, 1,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "health"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val more = if (t.alerts.size > 1) "\n+${t.alerts.size - 1} more on the Health tab" else ""
        val n = NotificationCompat.Builder(context, OdoSyncApp.CHANNEL_HEALTH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(top.title)
            .setContentText(top.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(top.message + more))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_HEALTH, n) }
    }

    companion object {
        const val NOTIF_HEALTH = 43
        private const val MIN_POLLS = 10
        private const val PROFILE_MAX_AGE_MS = 30L * 24 * 3600 * 1000

        fun describe(t: TripSummary, u: Units): String = buildString {
            append(u.fmtDistance(t.distanceKm))
            append(", ").append("%.1f min in boost".format(Locale.US, t.boostMinutes))
            append(", ").append(if (t.pullEvents == 0) "no timing pulls" else
                "${t.pullEvents} pull${if (t.pullEvents == 1) "" else "s"} (max ${Units.fmtDeg(t.maxPullDeg)}" +
                    (t.maxPullCylinder?.let { " cyl $it" } ?: "") + ")")
            t.peakBoostKpa?.let { append(", peak ").append(u.fmtPressure(it)) }
            t.iatMaxC?.let { append(", IAT ").append(u.fmtTemp(it)) }
            t.oilMaxC?.let { append(", oil ").append(u.fmtTemp(it)) }
        }
    }
}
