package cc.adamu.odosync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import cc.adamu.odosync.data.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * A "serial port" over BLE GATT for ELM327-style dongles (Veepeak OBDCheck BLE advertises as
 * "VEEPEAK" and exposes service FFF0 with notify FFF1 / write FFF2; other common layouts are
 * auto-detected).
 *
 * Coexistence with wireless Android Auto: we never bond, never scan, never raise the
 * connection priority (LOW_POWER = ~100 ms connection interval), and only exchange a few
 * dozen bytes per minute. The phone's classic Bluetooth (HFP calls) and Wi-Fi (AA projection)
 * are untouched.
 */
@SuppressLint("MissingPermission")
class BleSerial(private val context: Context, private val device: BluetoothDevice) {

    @Volatile private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Raw notification payloads from the dongle. */
    val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    private var connectResult: CompletableDeferred<Boolean>? = null
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var pendingDescriptor: CompletableDeferred<Boolean>? = null
    private val writeMutex = Mutex()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) { // late event from a previous connection object
                if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { g.close() }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                EventLog.log("BLE: link up to ${device.address}, discovering services")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (_connected.value) EventLog.log("BLE: disconnected (status $status)")
                _connected.value = false
                connectResult?.complete(false)
                pendingWrite?.complete(false)
                pendingDescriptor?.complete(false)
                runCatching { g.close() }
                gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                EventLog.log("BLE: service discovery failed ($status)")
                connectResult?.complete(false)
                return
            }
            val pick = pickCharacteristics(g)
            if (pick == null) {
                EventLog.log("BLE: no serial characteristics found; services=" +
                    g.services.joinToString { it.uuid.toString().substring(4, 8) })
                connectResult?.complete(false)
                return
            }
            val (notify, write) = pick
            writeChar = write
            writeType = if (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            EventLog.log("BLE: serial on ${short(notify.service.uuid)} notify=${short(notify.uuid)} write=${short(write.uuid)}")
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) {
                finishSetup(g); return
            }
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            pendingDescriptor = CompletableDeferred()
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pendingDescriptor?.complete(status == BluetoothGatt.GATT_SUCCESS)
            if (descriptor.uuid == CCCD) finishSetup(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(value.copyOf())
        }

        @Deprecated("Pre-API 33 path")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { incoming.trySend(it.copyOf()) }
        }
    }

    private fun finishSetup(g: BluetoothGatt) {
        // Lowest-duty link: ~100-125 ms interval. Plenty for a few ELM commands per minute and
        // the least possible airtime next to Android Auto's Bluetooth/Wi-Fi traffic.
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        _connected.value = true
        connectResult?.complete(true)
    }

    /**
     * Connect and set up notifications. [autoConnect] = false is a quick direct attempt;
     * true lets the controller reconnect in the background at low duty whenever the dongle
     * shows up (used while waiting so we don't hammer the radio).
     */
    suspend fun connect(autoConnect: Boolean, timeoutMs: Long): Boolean {
        close()
        val result = CompletableDeferred<Boolean>()
        connectResult = result
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(timeoutMs) { result.await() } ?: false
        if (!ok) close()
        return ok
    }

    suspend fun write(text: String): Boolean = writeMutex.withLock {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        val bytes = text.toByteArray(Charsets.US_ASCII)
        // ELM commands are short; still chunk to the default 20-byte ATT payload to be safe.
        for (chunk in bytes.toList().chunked(20)) {
            val arr = chunk.toByteArray()
            val done = CompletableDeferred<Boolean>()
            pendingWrite = done
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, arr, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = writeType
                @Suppress("DEPRECATION")
                c.value = arr
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!started) return false
            if (withTimeoutOrNull(2000) { done.await() } != true) return false
        }
        true
    }

    fun drain() {
        while (incoming.tryReceive().isSuccess) { /* discard */ }
    }

    fun close() {
        val g = gatt
        gatt = null
        writeChar = null
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        _connected.value = false
    }

    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        // Known layouts first.
        for ((svc, notify, write) in KNOWN) {
            val s = g.getService(uuid16(svc)) ?: continue
            val n = s.getCharacteristic(uuid16(notify)) ?: continue
            val w = s.getCharacteristic(uuid16(write)) ?: continue
            if (n.canNotify() && w.canWrite()) return n to w
        }
        // Otherwise any vendor service with a notify + write characteristic.
        for (s in g.services) {
            if (s.uuid.toString().startsWith("00001800") || s.uuid.toString().startsWith("00001801") ||
                s.uuid.toString().startsWith("0000180a")) continue
            val n = s.characteristics.firstOrNull { it.canNotify() } ?: continue
            val w = s.characteristics.firstOrNull { it.canWrite() } ?: continue
            return n to w
        }
        return null
    }

    private fun BluetoothGattCharacteristic.canNotify() =
        properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    private fun BluetoothGattCharacteristic.canWrite() =
        properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val KNOWN = listOf(
            Triple(0xFFF0, 0xFFF1, 0xFFF2), // Veepeak OBDCheck BLE, OBDLink CX, many ELM327 BLE
            Triple(0xFFE0, 0xFFE1, 0xFFE1), // HM-10 style clones
            Triple(0x18F0, 0x2AF0, 0x2AF1), // Vgate iCar Pro BLE
        )
        fun uuid16(v: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(v))
        private fun short(u: UUID) = u.toString().substring(4, 8).uppercase()

        /** Small helper so callers can back off without importing coroutines everywhere. */
        suspend fun pause(ms: Long) = delay(ms)
    }
}
