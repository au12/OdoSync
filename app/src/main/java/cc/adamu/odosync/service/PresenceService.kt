package cc.adamu.odosync.service

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.data.TripStore

/**
 * The system binds this when the associated dongle starts or stops advertising nearby.
 * Note: most BLE dongles stop advertising while connected, so "disappeared" during a session is
 * normal and ignored by TripService.
 */
class PresenceService : CompanionDeviceService() {

    private fun appeared(source: String) {
        val store = TripStore.get(this)
        val s = Settings.get(this).current
        // After we released the dongle for inactivity it starts advertising again; don't bounce
        // straight back in. A car Bluetooth connection or opening the app still wakes us.
        val sinceIdle = System.currentTimeMillis() - store.lastIdleStop()
        if (sinceIdle < 30 * 60_000L && !CarBtState.anyConnected(s.carBtMacs)) {
            EventLog.log("Dongle advertising ($source) - ignored, released it ${sinceIdle / 60_000} min ago")
            return
        }
        TripService.start(this, "dongle nearby ($source)")
    }

    private fun disappeared() {
        TripService.send(this, TripService.ACTION_DONGLE_GONE)
    }

    @Deprecated("API 31-32 callback")
    override fun onDeviceAppeared(address: String) = appeared("presence")

    @Deprecated("API 31-32 callback")
    override fun onDeviceDisappeared(address: String) = disappeared()

    @Deprecated("API 33-35 callback")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared("presence")

    @Deprecated("API 33-35 callback")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = disappeared()

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36) return
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> appeared("presence")
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED, DevicePresenceEvent.EVENT_BT_DISCONNECTED -> disappeared()
            else -> Unit
        }
    }
}
