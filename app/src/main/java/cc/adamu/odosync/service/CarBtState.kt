package cc.adamu.odosync.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Tracks whether the phone is linked to the car's Bluetooth (head unit / wireless AA adapter). */
object CarBtState {
    private val connected = mutableSetOf<String>()

    @Synchronized fun set(mac: String, isConnected: Boolean) {
        if (isConnected) connected += mac else connected -= mac
    }

    @Synchronized fun anyConnected(): Boolean = connected.isNotEmpty()

    /**
     * Is this the car? The devices picked in Settings; if none are picked, anything that
     * identifies itself as car audio / hands-free kit (head units and wireless AA adapters do).
     */
    @SuppressLint("MissingPermission")
    fun isCar(device: BluetoothDevice, macs: Set<String>): Boolean {
        if (macs.isNotEmpty()) return device.address in macs
        val cls = runCatching { device.bluetoothClass?.deviceClass }.getOrNull() ?: return false
        return cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || cls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
    }

    /** Initial state when the service starts after the link already came up. */
    @SuppressLint("MissingPermission")
    suspend fun probe(context: Context, macs: Set<String>): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        var found = false
        for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)) {
            val devices = withTimeoutOrNull(2000) {
                suspendCancellableCoroutine<List<BluetoothDevice>> { cont ->
                    val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                            val list = runCatching { proxy.connectedDevices.toList() }.getOrDefault(emptyList())
                            adapter.closeProfileProxy(p, proxy)
                            if (cont.isActive) cont.resume(list)
                        }
                        override fun onServiceDisconnected(p: Int) {}
                    }, profile)
                    if (!ok && cont.isActive) cont.resume(emptyList())
                }
            }.orEmpty()
            devices.filter { isCar(it, macs) }.forEach { set(it.address, true); found = true }
        }
        return found
    }
}
