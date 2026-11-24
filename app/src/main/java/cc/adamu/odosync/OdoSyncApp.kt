package cc.adamu.odosync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.ui.DonglePairing
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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRIPS, "Trip summaries", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Distance, time and LubeLogger upload result after each drive"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        UploadWorker.schedulePeriodic(this)
        // One-time: older versions observed dongle presence (background BLE scanning). Stop it.
        val prefs = getSharedPreferences("migrations", MODE_PRIVATE)
        if (!prefs.getBoolean("presenceOff", false)) {
            Settings.get(this).current.dongleMac?.let { DonglePairing.stopObservingPresence(this, it) }
            prefs.edit().putBoolean("presenceOff", true).apply()
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "trip"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_TRIPS = "trips"
    }
}
