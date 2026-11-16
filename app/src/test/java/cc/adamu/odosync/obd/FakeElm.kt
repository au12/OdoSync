package cc.adamu.odosync.obd

import cc.adamu.odosync.link.ObdLink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An ELM327 plus a BMW F-series car, in memory. Speaks enough of the ELM327 command set for
 * [Elm327] (headers on, spaces off, BMW extended addressing with ATSH/ATCEA/ATCRA) and answers
 * UDS 22 requests through [ecu], framing replies the way the real adapter prints them
 * ("612F1100B62..." lines, ISO-TP with the F1 tester byte first).
 */
class FakeElm(
    /** (ecu, dids) -> positive payload starting 0x62, or 0x7F 22 nrc, or null = silence. */
    private val ecu: (Int, List<Int>) -> IntArray?,
    private val delayMs: Long = 0,
) : ObdLink {
    override val label = "fake"
    private val _connected = MutableStateFlow(true)
    override val connected: StateFlow<Boolean> = _connected
    private val rx = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> = rx

    val sent = mutableListOf<String>()
    var fastModeCalls = mutableListOf<Boolean>()
    private var cea: Int? = null
    private var header = "7DF"

    override suspend fun write(text: String): Boolean {
        if (!_connected.value) return false
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        val cmd = text.trim().uppercase()
        sent += cmd
        rx.trySend((reply(cmd) + "\r\r>").toByteArray())
        return true
    }

    private fun reply(cmd: String): String = when {
        cmd == "ATZ" || cmd == "ATWS" -> "\r\rELM327 v1.5"
        cmd == "ATI" -> "ELM327 v1.5"
        cmd == "ATRV" -> "14.1V"
        cmd.startsWith("ATCEA") -> { cea = cmd.removePrefix("ATCEA").takeIf { it.isNotEmpty() }?.toInt(16); "OK" }
        cmd.startsWith("ATSH") -> { header = cmd.removePrefix("ATSH"); "OK" }
        cmd.startsWith("AT") -> "OK"
        cmd.startsWith("22") -> uds(cmd)
        cmd == "010C" -> "7E804410C0BB8"
        else -> "?"
    }

    private fun uds(cmd: String): String {
        val target = cea ?: return "NO DATA"
        val dids = cmd.drop(2).chunked(4).map { it.toInt(16) }
        val payload = ecu(target, dids) ?: return "NO DATA"
        return frames(0x600 + target, payload).joinToString("\r")
    }

    override fun drain() { while (rx.tryReceive().isSuccess) Unit }
    override fun close() { _connected.value = false }
    override fun setFastMode(fast: Boolean) { fastModeCalls += fast }

    companion object {
        /** ISO-TP frames with extended addressing, printed like ATH1 ATS0. */
        fun frames(id: Int, payload: IntArray): List<String> {
            fun line(bytes: List<Int>) = "%03X".format(id) + bytes.joinToString("") { "%02X".format(it) }
            if (payload.size <= 6) return listOf(line(listOf(0xF1, payload.size) + payload.toList()))
            val out = mutableListOf(line(listOf(0xF1, 0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(5)))
            var seq = 1
            payload.drop(5).chunked(6).forEach { chunk ->
                out += line(listOf(0xF1, 0x20 or (seq and 0x0F)) + chunk)
                seq++
            }
            return out
        }

        fun u16(v: Int) = intArrayOf((v shr 8) and 0xFF, v and 0xFF)
    }
}
