package cc.adamu.odosync.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.service.TripService
import java.util.regex.Pattern

/**
 * Companion-device pairing for the OBD dongle. This is not Bluetooth bonding (the dongle is
 * never paired in Android's Bluetooth settings and never shows up as an audio device); it just
 * tells Android "this app owns that BLE device", which unlocks presence wake-ups and
 * background foreground-service starts.
 */
class DonglePairing(private val activity: ComponentActivity) {

    private val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
    private var handled: String? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode != Activity.RESULT_OK) {
            EventLog.log("Dongle pairing cancelled")
            return@registerForActivityResult
        }
        val data = res.data ?: return@registerForActivityResult
        if (Build.VERSION.SDK_INT >= 33) {
            IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                ?.let { onAssociation(it); return@registerForActivityResult }
        }
        @Suppress("DEPRECATION")
        val p = IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_DEVICE, Parcelable::class.java)
        val device: BluetoothDevice? = when (p) {
            is ScanResult -> p.device
            is BluetoothDevice -> p
            else -> null
        }
        val name = (p as? ScanResult)?.scanRecord?.deviceName
        device?.let { saved(it.address, name ?: safeName(it), associationId = null) }
    }

    fun start() {
        handled = null
        val pattern = Settings.get(activity).current.dongleNamePattern
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(pattern))
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()
        val cb = object : CompanionDeviceManager.Callback() {
            @Deprecated("API < 33")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onDeviceFound(intentSender: IntentSender) {
                if (Build.VERSION.SDK_INT < 33) launch(intentSender)
            }

            override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                if (Build.VERSION.SDK_INT >= 33) onAssociation(associationInfo)
            }

            override fun onFailure(error: CharSequence?) {
                EventLog.log("Dongle pairing failed: $error (is the dongle plugged in and the car on?)")
            }
        }
        if (Build.VERSION.SDK_INT >= 33) cdm.associate(request, activity.mainExecutor, cb)
        else @Suppress("DEPRECATION") cdm.associate(request, cb, null)
    }

    private fun launch(sender: IntentSender) =
        launcher.launch(IntentSenderRequest.Builder(sender).build())

    @RequiresApi(33)
    private fun onAssociation(info: AssociationInfo) {
        val mac = info.deviceMacAddress?.toString()?.uppercase() ?: return
        saved(mac, info.displayName?.toString(), info.id)
    }

    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice): String? = runCatching { d.name }.getOrNull()

    private fun saved(mac: String, name: String?, associationId: Int?) {
        if (handled == mac) return
        handled = mac
        Settings.get(activity).update {
            putString("dongleMac", mac)
            putString("dongleName", name ?: "OBD dongle")
        }
        EventLog.log("Dongle paired: ${name ?: "?"} ($mac)")
        // Connect once now (you're in the car with the ignition on while pairing).
        TripService.start(activity, "dongle paired")
    }

    companion object {
        /**
         * Turn off companion presence observation (0.1.0/0.1.1 used it to wake on the dongle
         * advertising). That background BLE scanning is wasted battery now that only the car's
         * Bluetooth starts a session.
         */
        fun stopObservingPresence(context: Context, mac: String) {
            val cdm = context.getSystemService(CompanionDeviceManager::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 36) {
                    cdm.myAssociations.filter { it.deviceMacAddress?.toString().equals(mac, true) }.forEach {
                        cdm.stopObservingDevicePresence(
                            ObservingDevicePresenceRequest.Builder().setAssociationId(it.id).build())
                    }
                } else {
                    @Suppress("DEPRECATION")
                    cdm.stopObservingDevicePresence(mac)
                }
            }
        }

        fun unpair(context: Context) {
            val settings = Settings.get(context)
            val mac = settings.current.dongleMac ?: return
            val cdm = context.getSystemService(CompanionDeviceManager::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    cdm.myAssociations.filter { it.deviceMacAddress?.toString().equals(mac, true) }.forEach {
                        if (Build.VERSION.SDK_INT >= 36) {
                            cdm.stopObservingDevicePresence(
                                ObservingDevicePresenceRequest.Builder().setAssociationId(it.id).build())
                        } else {
                            @Suppress("DEPRECATION") cdm.stopObservingDevicePresence(mac)
                        }
                        cdm.disassociate(it.id)
                    }
                } else {
                    @Suppress("DEPRECATION") cdm.stopObservingDevicePresence(mac)
                    @Suppress("DEPRECATION") cdm.disassociate(mac)
                }
            }
            settings.update { remove("dongleMac"); remove("dongleName"); remove("odoSource"); remove("bmwVariableDlc") }
            TripService.send(context, TripService.ACTION_STOP)
            EventLog.log("Dongle unpaired")
        }

        fun isAssociated(context: Context, mac: String?): Boolean {
            if (mac == null) return false
            val cdm = context.getSystemService(CompanionDeviceManager::class.java)
            return runCatching {
                if (Build.VERSION.SDK_INT >= 33) cdm.myAssociations.any { it.deviceMacAddress?.toString().equals(mac, true) }
                else @Suppress("DEPRECATION") cdm.associations.any { it.equals(mac, true) }
            }.getOrDefault(false)
        }
    }
}
