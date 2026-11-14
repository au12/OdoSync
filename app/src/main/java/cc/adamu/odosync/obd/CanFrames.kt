package cc.adamu.odosync.obd

/**
 * Pure parsing of ELM327 output (ATH1, spaces on or off) into CAN frames, plus ISO-TP
 * (ISO 15765-2) reassembly. No Android dependencies so it is unit-tested on the JVM against
 * real F32/F36 captures.
 *
 * BMW F-series D-CAN uses *extended addressing*: the tester transmits on 0x6F1 with the target
 * ECU address as the first data byte (0x60 = KOMBI, 0x12 = DME), and the ECU answers on
 * 0x600 + its address with 0xF1 (the tester) as the first data byte, followed by the normal
 * ISO-TP PCI byte. Example (KOMBI, DID D10D):
 *
 *   660 F1 10 0B 62 D1 0D 00 01      <- first frame, 11-byte payload
 *   660 F1 21 52 94 00 01 52 94      <- consecutive frame #1
 *   payload = 62 D1 0D | 00 01 52 94 | 00 01 52 94  -> 0x00015294 = 86676 km
 */
object CanFrames {

    data class Frame(val id: Int, val data: IntArray) {
        override fun equals(other: Any?) = other is Frame && other.id == id && other.data.contentEquals(data)
        override fun hashCode() = id * 31 + data.contentHashCode()
        override fun toString() = "%03X ".format(id) + data.joinToString(" ") { "%02X".format(it) }
    }

    private val HEX = Regex("^[0-9A-Fa-f]+$")

    /** Status words the ELM327 prints instead of data. */
    val ERROR_WORDS = listOf(
        "NO DATA", "CAN ERROR", "BUS ERROR", "BUS INIT", "UNABLE TO CONNECT", "STOPPED",
        "BUFFER FULL", "FB ERROR", "DATA ERROR", "LV RESET", "ACT ALERT", "ERR", "?",
    )

    /**
     * Parse an ELM327 response (headers on, 11-bit IDs). Non-hex lines (SEARCHING..., OK,
     * NO DATA, etc.) are ignored. Works with ATS0 ("660F1100B62D10D0001") and ATS1
     * ("660 F1 10 0B 62 D1 0D 00 01").
     */
    fun parse(text: String): List<Frame> {
        val out = mutableListOf<Frame>()
        for (raw in text.split('\r', '\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // Some adapters prefix multi-line responses with a frame index "0:" - not with ATH1,
            // but be tolerant.
            val cleaned = line.substringAfter(':').replace(" ", "")
            if (!HEX.matches(cleaned)) continue
            if (cleaned.length < 5 || cleaned.length % 2 == 0) continue // need 3-char header + whole bytes
            val id = cleaned.substring(0, 3).toInt(16)
            val bytes = cleaned.substring(3).chunked(2).map { it.toInt(16) }.toIntArray()
            if (bytes.isEmpty() || bytes.size > 8) continue
            out += Frame(id, bytes)
        }
        return out
    }

    /**
     * Reassemble ISO-TP messages addressed from [rxId]. If [extendedAddressing] is true, a
     * leading 0xF1 byte (the tester address) is stripped from every frame before the PCI.
     * Returns every complete message in order (e.g. a 0x7F xx 0x78 "response pending"
     * followed by the real answer).
     */
    fun reassemble(frames: List<Frame>, rxId: Int, extendedAddressing: Boolean): List<IntArray> {
        val messages = mutableListOf<IntArray>()
        var buffer: MutableList<Int>? = null
        var expectedLen = 0
        var expectedSeq = 1
        for (f in frames) {
            if (f.id != rxId) continue
            var d = f.data
            if (extendedAddressing && d.isNotEmpty() && d[0] == 0xF1) d = d.copyOfRange(1, d.size)
            if (d.isEmpty()) continue
            val pci = d[0] shr 4
            when (pci) {
                0x0 -> { // single frame
                    val len = d[0] and 0x0F
                    if (len in 1..(d.size - 1)) messages += d.copyOfRange(1, 1 + len)
                    buffer = null
                }
                0x1 -> { // first frame
                    if (d.size < 2) continue
                    expectedLen = ((d[0] and 0x0F) shl 8) or d[1]
                    buffer = d.copyOfRange(2, d.size).toMutableList()
                    expectedSeq = 1
                    if (buffer!!.size >= expectedLen) {
                        messages += buffer!!.take(expectedLen).toIntArray(); buffer = null
                    }
                }
                0x2 -> { // consecutive frame
                    val b = buffer ?: continue
                    val seq = d[0] and 0x0F
                    if (seq != expectedSeq) { buffer = null; continue } // lost a frame: drop message
                    expectedSeq = (expectedSeq + 1) and 0x0F
                    b += d.copyOfRange(1, d.size).toList()
                    if (b.size >= expectedLen) {
                        messages += b.take(expectedLen).toIntArray(); buffer = null
                    }
                }
                else -> Unit // 0x3 = flow control echo, ignore
            }
        }
        return messages
    }

    fun be32(d: IntArray, offset: Int): Long =
        (d[offset].toLong() shl 24) or (d[offset + 1].toLong() shl 16) or
            (d[offset + 2].toLong() shl 8) or d[offset + 3].toLong()

    fun hasError(text: String): String? {
        val upper = text.uppercase()
        return ERROR_WORDS.firstOrNull { w -> upper.lines().any { it.trim() == w || it.trim().startsWith(w) } }
    }
}

/** Decoders for the specific values we read. */
object Decoders {
    /** UDS 0x62 response to 22 D1 0D from the KOMBI: two big-endian uint32 km counters. */
    fun kombiOdometerKm(payload: IntArray): Long? {
        if (payload.size < 7 || payload[0] != 0x62 || payload[1] != 0xD1 || payload[2] != 0x0D) return null
        val a = CanFrames.be32(payload, 3)
        val b = if (payload.size >= 11) CanFrames.be32(payload, 7) else a
        // Both copies normally agree; if not, trust the larger plausible one.
        return listOf(a, b).filter { it in 1..2_000_000 }.maxOrNull()
    }

    /** UDS 0x62 response to 22 55 8E from the DME: one big-endian uint32 km counter. */
    fun dmeOdometerKm(payload: IntArray): Long? {
        if (payload.size < 7 || payload[0] != 0x62 || payload[1] != 0x55 || payload[2] != 0x8E) return null
        return CanFrames.be32(payload, 3).takeIf { it in 1..2_000_000 }
    }

    /** Standard OBD-II mode 01 PID A6 (only on newer DME software): uint32 in 0.1 km. */
    fun obdPidA6Km(payload: IntArray): Long? {
        if (payload.size < 6 || payload[0] != 0x41 || payload[1] != 0xA6) return null
        return (CanFrames.be32(payload, 2) / 10).takeIf { it in 1..2_000_000 }
    }

    /** Standard OBD-II mode 01 PID 0C: engine speed (A*256+B)/4. */
    fun obdRpm(payload: IntArray): Int? {
        if (payload.size < 4 || payload[0] != 0x41 || payload[1] != 0x0C) return null
        return ((payload[2] shl 8) or payload[3]) / 4
    }

    /** "ATRV" → "12.6V" */
    fun voltage(text: String): Float? =
        Regex("""(\d{1,2}\.\d)\s*V""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
}
