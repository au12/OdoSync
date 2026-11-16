package cc.adamu.odosync.obd

import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.link.ObdLink
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Minimal ELM327 command layer. Two addressing modes are used:
 *  - BMW: header 6F1, extended address = target ECU, custom flow control (needed because
 *    every odometer answer is a multi-frame ISO-TP message).
 *  - STD: plain OBD-II to the DME on 7E0/7E8 (engine RPM, PID A6 fallback).
 * ATRV (adapter voltage) never touches the CAN bus, so it is safe to call while the car sleeps.
 *
 * Read-only by construction: anything that would reach the car's bus must be a UDS
 * ReadDataByIdentifier (22) or an OBD-II mode 01 request; everything else is refused before it
 * leaves the phone (see [requireReadOnly]).
 */
class Elm327(private val serial: ObdLink) {

    var version: String = ""
        private set

    private sealed interface Mode {
        data object Unknown : Mode
        data class Bmw(val ecu: Int) : Mode
        data object Std : Mode
    }

    private var mode: Mode = Mode.Unknown

    /**
     * false: BMW requests go out as standard ISO 15765-4 frames (ATSP6, DLC 8, padded).
     * true:  user protocol B with variable DLC (ATPB C1 01 / ATSP B), the variant used by the
     *        well-known Torque "BMW enhanced PID" init string. Auto-selected on first success.
     */
    var bmwVariableDlc: Boolean = false
        set(v) { if (field != v) { field = v; mode = Mode.Unknown } }

    /**
     * Payload bytes a request may carry. A plain ELM327 only transmits single CAN frames, and
     * BMW extended addressing spends one byte on the target address, which leaves 6: room for
     * "22" plus two DIDs.
     */
    val maxRequestBytes: Int get() = 6

    /** Send one command and return everything up to the '>' prompt, or null on timeout/link loss. */
    suspend fun send(cmd: String, timeoutMs: Long = 3000): String? {
        requireReadOnly(cmd)
        serial.drain()
        if (!serial.write(cmd + "\r")) return null
        val sb = StringBuilder()
        val done = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val chunk = serial.incoming.receive()
                sb.append(String(chunk, Charsets.US_ASCII))
                if (sb.indexOf(">") >= 0) break
            }
            true
        }
        if (done == null) {
            EventLog.log("ELM: '$cmd' timed out (got '${sb.toString().clean()}')")
            return null
        }
        var text = sb.substring(0, sb.indexOf(">")).replace("\u0000", "")
        // Strip a command echo if ATE0 has not taken effect yet.
        if (text.trimStart().startsWith(cmd, ignoreCase = true)) text = text.trimStart().substring(cmd.length)
        return text.trim()
    }

    suspend fun init(): Boolean {
        mode = Mode.Unknown
        val z = send("ATZ", 5000) ?: send("ATWS", 4000)
        if (z == null) {
            EventLog.log("ELM: no reply to reset")
            return false
        }
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATST96", "ATPBC101", "ATSP6", "ATCAF1", "ATCFC1")) {
            val r = send(c) ?: return false
            if (!r.contains("OK")) EventLog.log("ELM: '$c' -> '${r.clean()}' (continuing)")
        }
        version = send("ATI")?.clean().orEmpty()
        EventLog.log("ELM: ready ($version)")
        return true
    }

    suspend fun voltage(): Float? = send("ATRV", 2000)?.let(Decoders::voltage)

    /**
     * Point requests at BMW ECU [ecu]. Switching from one BMW ECU to another (cluster for the
     * odometer, engine for health data) only changes the receive filter, extended address and
     * flow-control address: three commands instead of the full seven.
     */
    suspend fun ensureBmw(ecu: Int): Boolean {
        val current = mode
        if (current == Mode.Bmw(ecu)) return true
        val e = "%02X".format(ecu)
        val ecuSpecific = listOf("ATCRA6$e", "ATCEA$e", "ATFCSD${e}300000")
        val cmds = if (current is Mode.Bmw) ecuSpecific else {
            val proto = if (bmwVariableDlc) "ATSPB" else "ATSP6"
            listOf(proto, "ATSH6F1") + ecuSpecific.take(2) + listOf("ATFCSH6F1", ecuSpecific[2], "ATFCSM1")
        }
        for (c in cmds) {
            val r = send(c)
            if (r == null || !r.contains("OK")) {
                EventLog.log("ELM: '$c' rejected ('${r?.clean()}'); adapter may not support BMW extended addressing")
                mode = Mode.Unknown
                return false
            }
        }
        mode = Mode.Bmw(ecu)
        return true
    }

    suspend fun ensureStd(): Boolean {
        if (mode == Mode.Std) return true
        var r = send("ATCEA") // no argument = extended addressing off
        if (r == null || !r.contains("OK")) {
            // Some clones dislike the bare form; a reset clears it reliably.
            if (!init()) return false
        }
        for (c in listOf("ATSP6", "ATFCSM0", "ATSH7E0", "ATCRA7E8")) {
            r = send(c)
            if (r == null || !r.contains("OK")) {
                mode = Mode.Unknown
                return false
            }
        }
        mode = Mode.Std
        return true
    }

    private fun String.clean() = replace("\r", " ").replace("\n", " ").trim()

    companion object {
        /**
         * Service IDs allowed onto the car's bus: 0x22 ReadDataByIdentifier and OBD-II mode 01.
         * Nothing that writes, resets, codes or changes sessions can be sent, whatever a future
         * caller asks for.
         */
        private val READ_ONLY_SERVICES = setOf("22", "01")

        fun requireReadOnly(cmd: String) {
            val c = cmd.trim().uppercase()
            if (c.startsWith("AT")) return
            val sid = c.replace(" ", "").take(2)
            require(sid in READ_ONLY_SERVICES) { "Refusing to send '$cmd': only read requests (22, 01) are allowed" }
        }
    }
}
