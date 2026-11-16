package cc.adamu.odosync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.upload.UploadWorker

class OdoSyncApp : Application(), Configuration.Provider {

    // On-demand WorkManager init (the default initializer is removed in the manifest).
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        // MIN importance: no sound, no heads-up, no status-bar icon, never mirrored to Android Auto.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Trip monitor", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while OdoSync is talking to the OBD dongle"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Problems", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Upload or pairing problems that need attention"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        // Engine-health findings, posted once the drive has ended (never mid-drive).
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HEALTH, "Engine health", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Timing pulls, boost and temperature findings after a drive"
                setShowBadge(true)
            }
        )
        UploadWorker.schedulePeriodic(this)
    }

    companion object {
        const val CHANNEL_SERVICE = "trip"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_HEALTH = "health"
    }
}
