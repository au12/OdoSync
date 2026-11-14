package cc.adamu.odosync.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.data.Settings

/**
 * Wireless Android Auto always starts with a Bluetooth link to the car, so the phone connecting
 * to the selected car device is the most dependable "I'm in the car" trigger. Starting the
 * foreground service from here is allowed because the app has a companion-device association.
 */
class CarBtReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            ?: return
        val settings = Settings.get(context).current
        if (device.address !in settings.carBtMacs) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                CarBtState.set(device.address, true)
                EventLog.log("Car Bluetooth connected (${device.address})")
                TripService.send(context, TripService.ACTION_CAR_BT) {
                    putExtra(TripService.EXTRA_CONNECTED, true)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                CarBtState.set(device.address, false)
                EventLog.log("Car Bluetooth disconnected (${device.address})")
                // Only poke a running service; don't spin one up just to say goodbye.
                if (cc.adamu.odosync.data.LiveStatus.current.serviceRunning) {
                    TripService.send(context, TripService.ACTION_CAR_BT) {
                        putExtra(TripService.EXTRA_CONNECTED, false)
                    }
                }
            }
        }
    }
}
