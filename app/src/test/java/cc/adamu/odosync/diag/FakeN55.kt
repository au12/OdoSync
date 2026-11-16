package cc.adamu.odosync.diag

import cc.adamu.odosync.obd.FakeElm
import kotlin.math.roundToInt

/**
 * A six-cylinder MEVD17 DME for the tests: holds an engine state and answers UDS 22 with the
 * same encodings as the OBDb captures (see DmeSignalsTest for the real-capture checks).
 */
class FakeN55(
    var multiDid: Boolean = true,
    /** DIDs that answer NRC 31 (requestOutOfRange). */
    val missing: Set<Int> = setOf(0x4015, 0x57C3, 0x4402, 0x4300),
) {
    var boostKpa = 100.0
    var targetKpa = 100.0
    var baroKpa = 100.0
    var iatC = 30.0
    var oilC = 95.0
    var coolantC = 92.0
    val timing = DoubleArray(6) { 30.0 }
    var railBar = 60.0
    var lowFuelBar = 5.8
    var lambda = 1.0
    var speedKmh = 60.0
    val misfires = IntArray(6)
    var odometerKm = 98_531L
    val requests = mutableListOf<List<Int>>()

    private fun u16(v: Int) = FakeElm.u16(v).toList()

    private fun data(did: Int): List<Int>? = when (did) {
        0x4205 -> u16((boostKpa * 128).roundToInt())
        0x4AB0 -> u16((targetKpa * 256).roundToInt().coerceAtMost(0xFFFF))
        0x4201 -> u16((baroKpa * 256).roundToInt())
        0x580F -> listOf(((iatC + 48) / 0.75).roundToInt())
        0x4408 -> u16(((oilC + 273.15) * 10).roundToInt())
        0x5805 -> u16(((coolantC + 273.15) * 10).roundToInt())
        0x4A49, 0x4A4A, 0x4A4C, 0x4A4D, 0x4A4E, 0x4A4F -> {
            val i = listOf(0x4A49, 0x4A4A, 0x4A4C, 0x4A4D, 0x4A4E, 0x4A4F).indexOf(did)
            u16((timing[i] * 10).roundToInt() and 0xFFFF)
        }
        0x56D7 -> u16((railBar * 50).roundToInt())
        0x58F3 -> u16((lowFuelBar * 1000).roundToInt())
        0x5889 -> u16((lambda * 65535 / 16).roundToInt())
        0x4AB1 -> u16((speedKmh * 100).roundToInt())
        0x4B23, 0x4B24, 0x4B25, 0x4B30, 0x4B31, 0x4B32 -> {
            val i = listOf(0x4B23, 0x4B24, 0x4B25, 0x4B30, 0x4B31, 0x4B32).indexOf(did)
            u16(misfires[i])
        }
        else -> null
    }

    fun respond(ecu: Int, dids: List<Int>): IntArray? {
        requests += dids
        if (ecu == 0x60 && dids == listOf(0xD10D)) {
            val km = odometerKm.toInt()
            val b = listOf(km ushr 24, (km shr 16) and 0xFF, (km shr 8) and 0xFF, km and 0xFF)
            return (listOf(0x62, 0xD1, 0x0D) + b + b).toIntArray()
        }
        if (ecu != 0x12) return null
        if (dids.size > 1 && !multiDid) return intArrayOf(0x7F, 0x22, 0x13)
        val out = mutableListOf(0x62)
        for (d in dids) {
            if (d in missing) continue
            val bytes = data(d) ?: continue
            out += (d shr 8) and 0xFF
            out += d and 0xFF
            out += bytes
        }
        return if (out.size == 1) intArrayOf(0x7F, 0x22, 0x31) else out.toIntArray()
    }
}
