package cc.adamu.odosync.obd

import cc.adamu.odosync.data.EventLog
import kotlinx.coroutines.delay

sealed interface DidResult {
    class Data(val payload: IntArray) : DidResult
    data class Negative(val nrc: Int) : DidResult
    /** Nothing answered (NO DATA / CAN ERROR): the car's bus is asleep or the ECU is off. */
    data object NoResponse : DidResult
    data class Error(val why: String) : DidResult
}

sealed interface OdoResult {
    data class Ok(val km: Long, val source: String) : OdoResult
    data object NoResponse : OdoResult
    data class Failed(val why: String) : OdoResult
}

sealed interface RpmResult {
    data class Ok(val rpm: Int) : RpmResult
    data object NoResponse : RpmResult
    data class Failed(val why: String) : RpmResult
}

/**
 * Reads the odometer from a BMW F-series car.
 *
 * Sources (confirmed on F32/F36 4 Series captures from the OBDb project):
 *  - KOMBI (instrument cluster, ECU 0x60), UDS 22 D1 0D -> 2x uint32 km. This is the number
 *    on the dash, so it is tried first.
 *  - DME (engine, ECU 0x12), UDS 22 55 8E -> uint32 km.
 *  - Standard OBD-II PID A6 (only on later DME software, e.g. MY2018+).
 * All reads are UDS ReadDataByIdentifier in the default session: read-only, no security
 * access, nothing written to the car.
 */
class CarReader(val elm: Elm327) {

    /** Called when the BMW frame variant that works for this car has been found. */
    var onFrameVariantLearned: ((Boolean) -> Unit)? = null

    data class Source(val id: String, val read: suspend CarReader.() -> DidResultOrKm)

    sealed interface DidResultOrKm {
        data class Km(val km: Long) : DidResultOrKm
        data class Raw(val r: DidResult) : DidResultOrKm
    }

    private val sources = listOf(
        Source("KOMBI") { bmwKm(0x60, 0xD10D, Decoders::kombiOdometerKm) },
        Source("DME") { bmwKm(0x12, 0x558E, Decoders::dmeOdometerKm) },
        Source("OBD-A6") { stdA6() },
    )

    suspend fun readDid(ecu: Int, did: Int): DidResult = readDids(ecu, listOf(did))

    /**
     * UDS ReadDataByIdentifier for one or more DIDs in a single request (the reply carries each
     * DID followed by its data; see [cc.adamu.odosync.diag.DmeSignals.split]). A plain ELM327 can
     * only send single-frame requests, so at most two DIDs fit (see [Elm327.maxRequestBytes]).
     */
    suspend fun readDids(ecu: Int, dids: List<Int>, timeoutMs: Long = 4000): DidResult {
        require(dids.isNotEmpty() && 1 + dids.size * 2 <= elm.maxRequestBytes) { "too many DIDs: $dids" }
        if (!elm.ensureBmw(ecu)) return DidResult.Error("adapter rejected BMW addressing")
        val cmd = "22" + dids.joinToString("") { "%04X".format(it) }
        repeat(2) { attempt ->
            val resp = elm.send(cmd, timeoutMs) ?: return DidResult.Error("adapter timeout")
            val frames = CanFrames.parse(resp)
            val msgs = CanFrames.reassemble(frames, 0x600 + ecu, extendedAddressing = true)
            val real = msgs.lastOrNull { !(it.size >= 3 && it[0] == 0x7F && it[2] == 0x78) }
            when {
                real == null && msgs.isNotEmpty() && attempt == 0 -> delay(300) // "response pending": ask again
                real == null -> return if (frames.isEmpty() && CanFrames.hasError(resp) != null)
                    DidResult.NoResponse else DidResult.Error("unparsed reply '${resp.take(80)}'")
                real[0] == 0x7F -> return DidResult.Negative(real.getOrElse(2) { 0 })
                real[0] == 0x62 -> return DidResult.Data(real)
                else -> return DidResult.Error("unexpected reply ${real.joinToString(" ") { "%02X".format(it) }}")
            }
        }
        return DidResult.NoResponse
    }

    private suspend fun bmwKm(ecu: Int, did: Int, decode: (IntArray) -> Long?): DidResultOrKm =
        when (val r = readDid(ecu, did)) {
            is DidResult.Data -> decode(r.payload)?.let { DidResultOrKm.Km(it) }
                ?: DidResultOrKm.Raw(DidResult.Error("implausible value"))
            else -> DidResultOrKm.Raw(r)
        }

    private suspend fun stdA6(): DidResultOrKm {
        if (!elm.ensureStd()) return DidResultOrKm.Raw(DidResult.Error("adapter rejected OBD mode"))
        val resp = elm.send("01A6", 3000) ?: return DidResultOrKm.Raw(DidResult.Error("adapter timeout"))
        val frames = CanFrames.parse(resp)
        val msg = CanFrames.reassemble(frames, 0x7E8, extendedAddressing = false).firstOrNull()
        if (msg == null) return DidResultOrKm.Raw(
            if (CanFrames.hasError(resp) != null) DidResult.NoResponse else DidResult.Error("unparsed '$resp'"))
        if (msg[0] == 0x7F) return DidResultOrKm.Raw(DidResult.Negative(msg.getOrElse(2) { 0 }))
        return Decoders.obdPidA6Km(msg)?.let { DidResultOrKm.Km(it) }
            ?: DidResultOrKm.Raw(DidResult.Error("implausible A6"))
    }

    /**
     * Try the remembered source first. If the first source gets no answer at all the bus is
     * asleep, so we stop instead of firing more frames at a sleeping car.
     */
    suspend fun readOdometer(preferred: String?): OdoResult {
        val ordered = sources.sortedBy { if (it.id == preferred) 0 else 1 }
        val reasons = mutableListOf<String>()
        for ((i, src) in ordered.withIndex()) {
            var r = src.read(this)
            if (i == 0 && preferred == null && src.id != "OBD-A6" &&
                (r as? DidResultOrKm.Raw)?.r == DidResult.NoResponse
            ) {
                // First ever read and the cluster is silent. If the engine ECU answers a plain
                // OBD request, the car is awake and it's our frame format: try the other one.
                if (readRpm() is RpmResult.Ok) {
                    elm.bmwVariableDlc = !elm.bmwVariableDlc
                    EventLog.log("BMW request unanswered while the car is awake; retrying with " +
                        if (elm.bmwVariableDlc) "variable-length frames" else "padded frames")
                    r = src.read(this)
                    if (r is DidResultOrKm.Km) onFrameVariantLearned?.invoke(elm.bmwVariableDlc)
                    else elm.bmwVariableDlc = !elm.bmwVariableDlc
                }
            }
            when (r) {
                is DidResultOrKm.Km -> return OdoResult.Ok(r.km, src.id)
                is DidResultOrKm.Raw -> when (val d = r.r) {
                    DidResult.NoResponse -> {
                        if (i == 0) return OdoResult.NoResponse
                        reasons += "${src.id}: no response"
                    }
                    is DidResult.Negative -> reasons += "${src.id}: NRC %02X".format(d.nrc)
                    is DidResult.Error -> reasons += "${src.id}: ${d.why}"
                    is DidResult.Data -> reasons += "${src.id}: bad data"
                }
            }
        }
        EventLog.log("Odometer read failed: ${reasons.joinToString("; ")}")
        return OdoResult.Failed(reasons.joinToString("; "))
    }

    /** Engine speed via plain OBD-II. 0 while auto start/stop has the engine off. */
    suspend fun readRpm(): RpmResult {
        if (!elm.ensureStd()) return RpmResult.Failed("adapter rejected OBD mode")
        val resp = elm.send("010C", 3000) ?: return RpmResult.Failed("adapter timeout")
        val frames = CanFrames.parse(resp)
        val msg = CanFrames.reassemble(frames, 0x7E8, extendedAddressing = false)
            .firstOrNull { it.size >= 4 && it[0] == 0x41 && it[1] == 0x0C }
        return when {
            msg != null -> RpmResult.Ok(Decoders.obdRpm(msg) ?: 0)
            CanFrames.hasError(resp) != null -> RpmResult.NoResponse
            else -> RpmResult.Failed("unparsed '$resp'")
        }
    }

    suspend fun voltage(): Float? = elm.voltage()
}
