package cc.adamu.odosync.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cc.adamu.odosync.OdoSyncApp
import cc.adamu.odosync.R
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.data.SettingsSnapshot
import cc.adamu.odosync.data.TripStore
import cc.adamu.odosync.data.TripSummary
import cc.adamu.odosync.data.UploadOutcome
import cc.adamu.odosync.data.UploadUnit
import cc.adamu.odosync.ui.MainActivity
import java.text.NumberFormat
import java.util.Locale

/**
 * The dismissable "Trip complete" notification. Posted when a trip ends, then updated in place
 * by the upload worker with the LubeLogger result - unless you've already swiped it away.
 */
object TripNotifier {
    const val ID = 43

    /** Post (or refresh) the summary of the last trip. [onlyIfShowing]: don't resurrect a dismissed one. */
    @SuppressLint("MissingPermission")
    fun show(context: Context, onlyIfShowing: Boolean = false) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val sum = TripStore.get(context).current.summary ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (onlyIfShowing && nm.activeNotifications.none { it.id == ID }) return

        val s = Settings.get(context).current
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = "Trip complete · +${distance(sum.distanceKm, s.uploadUnit)}"
        val stats = stats(sum, s)
        val upload = uploadLine(sum, s)
        val n = NotificationCompat.Builder(context, OdoSyncApp.CHANNEL_TRIPS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(upload)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$stats\n$upload"))
            .setSubText(if (sum.upload in failed) "Upload problem" else null)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(sum.endAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(ID, n)
    }

    private val failed = setOf(UploadOutcome.FAILED, UploadOutcome.RETRYING, UploadOutcome.NOT_CONFIGURED)

    fun distance(km: Long, unit: UploadUnit): String = when (unit) {
        UploadUnit.MILES -> String.format(Locale.getDefault(), "%.1f mi", km * SettingsSnapshot.KM_TO_MI)
        UploadUnit.KILOMETERS -> "$km km"
    }

    private fun stats(sum: TripSummary, s: SettingsSnapshot): String {
        val mins = ((sum.endAt - sum.startAt) / 60_000).coerceAtLeast(0)
        val duration = if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
        val odo = NumberFormat.getIntegerInstance().format(s.toUploadValue(sum.endKm)) + " " + s.uploadUnit.label
        val parts = mutableListOf(duration)
        if (mins >= 2 && sum.distanceKm > 0) {
            val kmh = sum.distanceKm * 60.0 / mins
            parts += if (s.uploadUnit == UploadUnit.MILES) "avg %.0f mph".format(kmh * SettingsSnapshot.KM_TO_MI)
            else "avg %.0f km/h".format(kmh)
        }
        parts += "odometer $odo"
        return parts.joinToString(" · ")
    }

    private fun uploadLine(sum: TripSummary, s: SettingsSnapshot): String {
        val value = NumberFormat.getIntegerInstance().format(s.toUploadValue(sum.endKm)) + " " + s.uploadUnit.label
        return when (sum.upload) {
            UploadOutcome.QUEUED -> "Uploading to LubeLogger…"
            UploadOutcome.WAITING_WIFI -> "Will upload to LubeLogger on Wi-Fi"
            UploadOutcome.UPLOADED -> "✓ Uploaded $value to LubeLogger"
            UploadOutcome.ALREADY_THERE -> "✓ LubeLogger already up to date"
            UploadOutcome.BELOW_MIN -> "Not uploaded - under the minimum change"
            UploadOutcome.NOT_CONFIGURED -> "Not uploaded - LubeLogger isn't set up"
            UploadOutcome.RETRYING -> "Upload failed, will retry: ${sum.uploadMessage}".trimEnd(':', ' ')
            UploadOutcome.FAILED -> "Upload failed: ${sum.uploadMessage}".trimEnd(':', ' ')
        }
    }
}
