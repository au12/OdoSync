package cc.adamu.odosync.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses below are real captures from F32/F36 4 Series cars published by the OBDb project
 * (github.com/OBDb/BMW-4-Series, tests/test_cases/2014 and 2019).
 */
class CanFramesTest {

    private fun kombi(text: String) =
        CanFrames.reassemble(CanFrames.parse(text), 0x660, extendedAddressing = true).single()

    @Test fun kombiOdometer_2014_noSpaces() {
        val msg = kombi("660F1100B62D10D0001\r660F121529400015294\r\r")
        assertEquals(11, msg.size)
        assertEquals(86676L, Decoders.kombiOdometerKm(msg))
    }

    @Test fun kombiOdometer_withSpaces() {
        val msg = kombi("660 F1 10 0B 62 D1 0D 00 01\r660 F1 21 FE A5 00 01 FE A5\r")
        assertEquals(130725L, Decoders.kombiOdometerKm(msg))
    }

    @Test fun kombiOdometer_highMileage() {
        assertEquals(202183L, Decoders.kombiOdometerKm(kombi("660F1100B62D10D0003\n660F12115C7000315C7")))
        assertEquals(151513L, Decoders.kombiOdometerKm(kombi("660F1100B62D10D0002\n660F1214FD900024FD9")))
    }

    @Test fun dmeOdometer() {
        val msgs = CanFrames.reassemble(
            CanFrames.parse("612F1100762558E0001\r612F1217C68FFFFFFFF\r"), 0x612, extendedAddressing = true)
        assertEquals(97384L, Decoders.dmeOdometerKm(msgs.single()))
    }

    @Test fun ignoresOtherIdsAndNoise() {
        val text = "SEARCHING...\r7E8 03 41 0C 00\r660F1100B62D10D0001\rBUFFER FULL\r660F121529400015294\r"
        val msg = CanFrames.reassemble(CanFrames.parse(text), 0x660, true).single()
        assertEquals(86676L, Decoders.kombiOdometerKm(msg))
    }

    @Test fun lostConsecutiveFrameDropsMessage() {
        val msgs = CanFrames.reassemble(CanFrames.parse("660F1100B62D10D0001\r660F122529400015294\r"), 0x660, true)
        assertTrue(msgs.isEmpty())
    }

    @Test fun negativeAndPendingResponses() {
        val msgs = CanFrames.reassemble(CanFrames.parse("660F1037F2278\r660F1037F2231\r"), 0x660, true)
        assertEquals(2, msgs.size)
        assertEquals(0x78, msgs[0][2])
        assertEquals(0x31, msgs[1][2])
    }

    @Test fun standardRpmAndA6() {
        val rpm = CanFrames.reassemble(CanFrames.parse("7E804410C0BB8\r"), 0x7E8, false).single()
        assertEquals(750, Decoders.obdRpm(rpm))
        val a6 = CanFrames.reassemble(CanFrames.parse("7E80641A6000F1C64\r"), 0x7E8, false).single()
        assertEquals(99030L, Decoders.obdPidA6Km(a6)) // 0x000F1C64 = 990308 x 0.1 km
    }

    @Test fun errorDetection() {
        assertNotNull(CanFrames.hasError("NO DATA"))
        assertNotNull(CanFrames.hasError("CAN ERROR\r"))
        assertNotNull(CanFrames.hasError("?"))
        assertNull(CanFrames.hasError("660F1100B62D10D0001"))
    }

    @Test fun voltage() {
        assertEquals(12.6f, Decoders.voltage("12.6V")!!, 0.001f)
        assertEquals(14.2f, Decoders.voltage(" 14.2V ")!!, 0.001f)
        assertNull(Decoders.voltage("?"))
    }

    @Test fun implausibleOdometerRejected() {
        val msg = kombi("660F1100B62D10D0000\r660F121000000000000\r")
        assertNull(Decoders.kombiOdometerKm(msg))
    }
}
