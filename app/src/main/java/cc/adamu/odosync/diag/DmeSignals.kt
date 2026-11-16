package cc.adamu.odosync.diag

/**
 * What OdoSync reads from the 435i's engine computer (DME, MEVD17.2, ECU 0x12) for the health
 * monitor. Every value is a UDS ReadDataByIdentifier in the default session: read-only, no
 * security access, the same requests BMW's own testers and apps like BimmerLink use.
 *
 * DIDs and scalings come from real F-series captures in the OBDb project
 * (github.com/OBDb/BMW-4-Series, BMW-3-Series and siblings); the unit tests replay them. Where
 * DME software generations disagree (oil and coolant temperature are sometimes plain °C × 10,
 * sometimes kelvin × 10 or × 100), the decoder tells the encodings apart by range, because no
 * real reading lands in the gap between them.
 */
enum class Sig(val label: String, val unit: String) {
    BOOST("Boost pressure", "kPa"),           // absolute, upstream of the throttle
    BOOST_TARGET("Boost target", "kPa"),      // absolute
    BARO("Barometric pressure", "kPa"),
    IAT("Intake air temperature", "°C"),
    OIL("Oil temperature", "°C"),
    COOLANT("Coolant temperature", "°C"),
    TIMING_1("Ignition timing, cyl 1", "°"),
    TIMING_2("Ignition timing, cyl 2", "°"),
    TIMING_3("Ignition timing, cyl 3", "°"),
    TIMING_4("Ignition timing, cyl 4", "°"),
    TIMING_5("Ignition timing, cyl 5", "°"),
    TIMING_6("Ignition timing, cyl 6", "°"),
    RAIL("Fuel rail pressure", "bar"),
    LOW_FUEL("Low-side fuel pressure", "bar"),
    LAMBDA("Lambda", ""),
    SPEED("Vehicle speed", "km/h"),
    MISFIRE_1("Misfire counter 1", ""),
    MISFIRE_2("Misfire counter 2", ""),
    MISFIRE_3("Misfire counter 3", ""),
    MISFIRE_4("Misfire counter 4", ""),
    MISFIRE_5("Misfire counter 5", ""),
    MISFIRE_6("Misfire counter 6", "");

    companion object {
        val TIMING = listOf(TIMING_1, TIMING_2, TIMING_3, TIMING_4, TIMING_5, TIMING_6)
        val MISFIRE = listOf(MISFIRE_1, MISFIRE_2, MISFIRE_3, MISFIRE_4, MISFIRE_5, MISFIRE_6)
        fun timingIndex(s: Sig): Int = TIMING.indexOf(s)
    }
}

/** One way of reading a [Sig]: a DID plus how to turn its data bytes into a number. */
class DidDef(
    val did: Int,
    /** Data bytes after the DID, when known up front. null = learnt from the first reply. */
    val length: Int?,
    val decode: (IntArray) -> Double?,
) {
    val hex: String get() = "%04X".format(did)
}

object DmeSignals {
    const val DME = 0x12

    private fun u8(d: IntArray, i: Int = 0): Int? = d.getOrNull(i)
    private fun u16(d: IntArray, i: Int = 0): Int? =
        if (d.size >= i + 2) (d[i] shl 8) or d[i + 1] else null
    private fun s16(d: IntArray, i: Int = 0): Int? = u16(d, i)?.let { if (it >= 0x8000) it - 0x10000 else it }

    private fun Double.within(lo: Double, hi: Double): Double? = takeIf { it in lo..hi }

    /**
     * Temperature in a 16-bit word, in whichever encoding this DME uses:
     *  - raw < 2000:            °C × 10       (older MEVD17 software, e.g. 03F9 = 101.7 °C)
     *  - 2000 ≤ raw < 20000:    kelvin × 10   (0DFE = 358.2 K = 85.05 °C)
     *  - raw ≥ 20000:           kelvin × 100  (newer DMEs)
     * The bands can't overlap for anything between -70 and +200 °C.
     */
    fun temp16(d: IntArray): Double? {
        val raw = u16(d) ?: return null
        if (raw == 0xFFFF) return null
        val c = when {
            raw < 2000 -> raw / 10.0
            raw < 20000 -> raw / 10.0 - 273.15
            else -> raw / 100.0 - 273.15
        }
        return c.within(-60.0, 250.0)
    }

    /** 0.75 °C per bit, offset -48: the classic BMW 8-bit temperature. */
    private fun temp8(d: IntArray): Double? = u8(d)?.let { it * 0.75 - 48 }?.within(-48.0, 143.25)

    private fun timing(d: IntArray): Double? = s16(d)?.let { it / 10.0 }?.within(-60.0, 80.0)

    private fun misfire(d: IntArray): Double? = u16(d)?.takeIf { it != 0xFFFF }?.toDouble()

    /** Candidate DIDs per signal, best first. Discovery keeps the first one the car answers sensibly. */
    val catalog: Map<Sig, List<DidDef>> = linkedMapOf(
        Sig.BOOST to listOf(DidDef(0x4205, 2) { d -> u16(d)?.let { it / 128.0 }?.within(20.0, 500.0) }),
        Sig.BOOST_TARGET to listOf(DidDef(0x4AB0, 2) { d -> u16(d)?.let { it / 256.0 }?.within(0.0, 256.0) }),
        Sig.BARO to listOf(DidDef(0x4201, 2) { d -> u16(d)?.let { it / 256.0 }?.within(50.0, 110.0) }),
        Sig.IAT to listOf(
            DidDef(0x580F, 1, ::temp8),
            DidDef(0x4015, null) { d -> u8(d, 1)?.toDouble()?.within(-40.0, 150.0) },
        ),
        Sig.OIL to listOf(
            DidDef(0x4408, 2, ::temp16),
            DidDef(0x57C3, 2) { d -> u16(d)?.let { it / 10.0 }?.within(-40.0, 200.0) },
            DidDef(0x4402, null) { d -> u8(d, 1)?.let { it / 2.0 } },
        ),
        Sig.COOLANT to listOf(
            DidDef(0x5805, 2, ::temp16),
            // "Engine temperature": one byte on older software, a kelvin word on newer.
            DidDef(0x4300, null) { d -> if (d.size == 1) temp8(d) else temp16(d) },
        ),
        Sig.TIMING_1 to listOf(DidDef(0x4A49, 2, ::timing)),
        Sig.TIMING_2 to listOf(DidDef(0x4A4A, 2, ::timing)),
        Sig.TIMING_3 to listOf(DidDef(0x4A4C, 2, ::timing)),
        Sig.TIMING_4 to listOf(DidDef(0x4A4D, 2, ::timing)),
        // Cylinders 5 and 6: OBDb's captures come from four-cylinder cars. 4A4E/4A4F are the next
        // two IDs in the same block (4A4B in between is engine load) and answer on six-cylinder
        // DMEs; TripAnalyzer only trusts them after they've tracked cylinders 1-4 on a drive.
        Sig.TIMING_5 to listOf(DidDef(0x4A4E, 2, ::timing)),
        Sig.TIMING_6 to listOf(DidDef(0x4A4F, 2, ::timing)),
        Sig.RAIL to listOf(DidDef(0x56D7, 2) { d -> u16(d)?.let { it / 50.0 }?.within(0.0, 400.0) }),
        Sig.LOW_FUEL to listOf(DidDef(0x58F3, 2) { d -> u16(d)?.let { it / 1000.0 }?.within(0.0, 20.0) }),
        Sig.LAMBDA to listOf(DidDef(0x5889, 2) { d -> u16(d)?.let { it * 16.0 / 65535.0 }?.within(0.4, 3.0) }),
        Sig.SPEED to listOf(DidDef(0x4AB1, 2) { d -> u16(d)?.let { it / 100.0 }?.within(0.0, 330.0) }),
        // Per-cylinder misfire counters; 4B25 jumps to 4B30 in BMW's numbering. Four-cylinder
        // cars answer FFFF for counters 5 and 6.
        Sig.MISFIRE_1 to listOf(DidDef(0x4B23, 2, ::misfire)),
        Sig.MISFIRE_2 to listOf(DidDef(0x4B24, 2, ::misfire)),
        Sig.MISFIRE_3 to listOf(DidDef(0x4B25, 2, ::misfire)),
        Sig.MISFIRE_4 to listOf(DidDef(0x4B30, 2, ::misfire)),
        Sig.MISFIRE_5 to listOf(DidDef(0x4B31, 2, ::misfire)),
        Sig.MISFIRE_6 to listOf(DidDef(0x4B32, 2, ::misfire)),
    )

    private val byDid: Map<Int, DidDef> = catalog.values.flatten().associateBy { it.did }

    fun def(did: Int): DidDef? = byDid[did]

    /**
     * Split a positive 0x62 reply into per-DID data. [lengths] gives each DID's data length
     * (from the catalog or learnt in discovery). Stops at the first DID it can't size, so a
     * garbled tail never shifts the values that came before it.
     */
    fun split(payload: IntArray, lengths: (Int) -> Int?): Map<Int, IntArray> {
        if (payload.isEmpty() || payload[0] != 0x62) return emptyMap()
        val out = linkedMapOf<Int, IntArray>()
        var i = 1
        while (i + 2 <= payload.size) {
            val did = (payload[i] shl 8) or payload[i + 1]
            val len = lengths(did) ?: break
            if (i + 2 + len > payload.size) break
            out[did] = payload.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        return out
    }
}
