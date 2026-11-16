package cc.adamu.odosync.diag

import cc.adamu.odosync.obd.CanFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding against real DME replies captured from F-series cars by the OBDb project
 * (github.com/OBDb/BMW-4-Series and BMW-3-Series / BMW-2-Series / BMW-1-Series test cases).
 * Each string is exactly what the ELM327 printed (ATH1, ATS0).
 */
class DmeSignalsTest {

    private fun decode(sig: Sig, frame: String): Double? {
        val msg = CanFrames.reassemble(CanFrames.parse(frame), 0x612, extendedAddressing = true).single()
        val def = DmeSignals.catalog.getValue(sig).first { it.did == ((msg[1] shl 8) or msg[2]) }
        val data = DmeSignals.split(msg) { if (it == def.did) msg.size - 3 else null }.getValue(def.did)
        return def.decode(data)
    }

    @Test fun ignitionTimingPerCylinder() {
        assertEquals(1.6, decode(Sig.TIMING_1, "612F105624A490010")!!, 1e-9)
        assertEquals(-22.5, decode(Sig.TIMING_1, "612F105624A49FF1F")!!, 1e-9) // cold-start cat heating
        assertEquals(-0.1, decode(Sig.TIMING_1, "612F105624A49FFFF")!!, 1e-9)
        assertEquals(0.8, decode(Sig.TIMING_2, "612F105624A4A0008")!!, 1e-9)
        assertEquals(-21.6, decode(Sig.TIMING_3, "612F105624A4CFF28")!!, 1e-9)
        assertEquals(-25.4, decode(Sig.TIMING_4, "612F105624A4DFF02")!!, 1e-9)
    }

    @Test fun boostTargetAndBaro() {
        assertEquals(100.65625, decode(Sig.BOOST, "612F1056242053254")!!, 1e-6)
        assertEquals(102.0, decode(Sig.BOOST, "612F1056242053300")!!, 1e-6)
        assertEquals(225.375, decode(Sig.BOOST_TARGET, "612F105624AB0E160")!!, 1e-6) // ~1.25 bar of boost asked for
        assertEquals(0.0, decode(Sig.BOOST_TARGET, "612F105624AB00000")!!, 1e-6)
        assertEquals(100.25, decode(Sig.BARO, "612F1056242016440")!!, 1e-6)
    }

    @Test fun intakeAir() {
        assertEquals(40.5, decode(Sig.IAT, "612F10462580F76")!!, 1e-9)
        assertEquals(42.0, decode(Sig.IAT, "612F10462580F78")!!, 1e-9)
    }

    @Test fun oilTemperatureBothEncodings() {
        // 2017+ software: kelvin x 10 (0x0DFE = 358.2 K)
        assertEquals(85.05, decode(Sig.OIL, "612F1056244080DFE")!!, 1e-9)
        assertEquals(87.15, decode(Sig.OIL, "612F1056244080E13")!!, 1e-9)
        // Older MEVD17 software: plain °C x 10
        assertEquals(101.7, decode(Sig.OIL, "612F10562440803F9")!!, 1e-9)
        assertEquals(102.6, decode(Sig.OIL, "612F1056244080402")!!, 1e-9)
        assertEquals(51.2, decode(Sig.OIL, "612F1056244080200")!!, 1e-9)
    }

    @Test fun coolant() {
        assertEquals(56.85, decode(Sig.COOLANT, "612F1056258050CE4")!!, 1e-9)
        assertEquals(84.15, decode(Sig.COOLANT, "612F1056258050DF5")!!, 1e-9)
    }

    @Test fun fuelLambdaSpeed() {
        assertEquals(172.34, decode(Sig.RAIL, "612F1056256D721A9")!!, 1e-9)
        assertEquals(99.98, decode(Sig.RAIL, "612F1056256D71387")!!, 1e-9)
        assertEquals(6.8, decode(Sig.LOW_FUEL, "612F1056258F31A90")!!, 1e-9)
        assertEquals(1.0088, decode(Sig.LAMBDA, "612F1056258891024")!!, 1e-4)
    }

    @Test fun misfireCounters() {
        assertEquals(2.0, decode(Sig.MISFIRE_2, "612F105624B240002")!!, 1e-9)
        assertEquals(0.0, decode(Sig.MISFIRE_1, "612F105624B230000")!!, 1e-9)
        // Four-cylinder cars answer FFFF for counters 5 and 6: not a count.
        assertNull(decode(Sig.MISFIRE_5, "612F105624B31FFFF"))
    }

    @Test fun splitTwoDidReply() {
        // 62 | 4A49 0010 | 4A4A FF15  (cyl 1 +1.6°, cyl 2 -23.5°)
        val payload = intArrayOf(0x62, 0x4A, 0x49, 0x00, 0x10, 0x4A, 0x4A, 0xFF, 0x15)
        val parts = DmeSignals.split(payload) { 2 }
        assertEquals(setOf(0x4A49, 0x4A4A), parts.keys)
        assertEquals(-23.5, DmeSignals.def(0x4A4A)!!.decode(parts.getValue(0x4A4A))!!, 1e-9)
    }

    @Test fun splitStopsAtUnknownDid() {
        val payload = intArrayOf(0x62, 0x42, 0x05, 0x32, 0x54, 0x99, 0x99, 0x01)
        val parts = DmeSignals.split(payload) { if (it == 0x4205) 2 else null }
        assertEquals(setOf(0x4205), parts.keys)
    }

    @Test fun temperatureBandsNeverOverlap() {
        for (c in -40..200) {
            val plain = (c * 10).coerceAtLeast(0)
            // Plain °C x 10 covers 0-199.9 °C; anything hotter would read as kelvin (not a real oil temperature).
            if (c in 0..199) assertEquals(c.toDouble(), DmeSignals.temp16(intArrayOf(plain shr 8, plain and 0xFF))!!, 0.05)
            val k10 = ((c + 273.15) * 10).toInt()
            assertEquals(c.toDouble(), DmeSignals.temp16(intArrayOf(k10 shr 8, k10 and 0xFF))!!, 0.11)
            val k100 = ((c + 273.15) * 100).toInt()
            assertEquals(c.toDouble(), DmeSignals.temp16(intArrayOf(k100 shr 8, k100 and 0xFF))!!, 0.011)
        }
        assertTrue(DmeSignals.temp16(intArrayOf(0xFF, 0xFF)) == null)
    }
}
