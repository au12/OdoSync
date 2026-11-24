package cc.adamu.odosync.service

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build

/**
 * Companion presence callbacks (the dongle advertising nearby). Unused since 0.1.2 apart from
 * a guard; presence observation is switched off at startup.
 */
class PresenceService : CompanionDeviceService() {

    // Presence wake-ups are no longer used: OdoSync only connects once the phone is on the car's
    // Bluetooth (CarBtReceiver), so it never holds the dongle at home. Kept so an observation
    // left over from an older version can't start anything by itself.
    private fun appeared(source: String) {
        if (CarBtState.anyConnected()) TripService.start(this, "dongle nearby ($source)")
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
