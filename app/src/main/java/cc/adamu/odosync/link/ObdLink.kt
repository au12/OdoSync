package cc.adamu.odosync.link

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/**
 * A byte pipe to an ELM327-compatible adapter. [cc.adamu.odosync.obd.Elm327] only needs this, so
 * the same command layer runs over:
 *  - [cc.adamu.odosync.ble.BleSerial]: Bluetooth LE (Veepeak OBDCheck BLE today; the WiCAN Pro's
 *    BLE service is recognised too),
 *  - [TcpLink]: ELM327 over Wi-Fi (WiCAN Pro, port 35000),
 *  - an in-memory fake in the unit tests.
 */
interface ObdLink {
    /** Short description for the log, e.g. "BLE 8C:DE:52:A1:07:3F" or "Wi-Fi 192.168.0.10:35000". */
    val label: String

    val connected: StateFlow<Boolean>

    /** Raw chunks from the adapter, in arrival order. */
    val incoming: ReceiveChannel<ByteArray>

    suspend fun write(text: String): Boolean

    /** Discards anything received but not yet read (stale replies, late "STOPPED" lines). */
    fun drain()

    fun close()

    /**
     * Polling hint. true while the app reads the engine several times a second (under boost),
     * false otherwise. BLE maps it to the connection interval; other links ignore it.
     */
    fun setFastMode(fast: Boolean) {}
}

/** Which adapter the car has. Stored in settings by name. */
enum class AdapterKind(val label: String, val isBle: Boolean) {
    VEEPEAK_BLE("Veepeak OBDCheck BLE", true),
    WICAN_PRO_BLE("WiCAN Pro (Bluetooth LE)", true),
    WICAN_PRO_WIFI("WiCAN Pro (Wi-Fi)", false);

    companion object {
        fun from(name: String?): AdapterKind = entries.firstOrNull { it.name == name } ?: VEEPEAK_BLE
    }
}
