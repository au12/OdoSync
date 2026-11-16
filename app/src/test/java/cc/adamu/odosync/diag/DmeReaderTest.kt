package cc.adamu.odosync.diag

import cc.adamu.odosync.obd.CarReader
import cc.adamu.odosync.obd.Elm327
import cc.adamu.odosync.obd.FakeElm
import cc.adamu.odosync.obd.OdoResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discovery and reads through the real Elm327/CarReader stack against an in-memory ELM327 + DME. */
class DmeReaderTest {

    private fun setup(car: FakeN55): Triple<FakeElm, CarReader, DmeReader> {
        val elm = FakeElm(car::respond)
        val reader = CarReader(Elm327(elm))
        return Triple(elm, reader, DmeReader(reader))
    }

    @Test fun discoversSixCylinderDmeWithBatching() = runBlocking {
        val car = FakeN55(multiDid = true)
        val (elm, _, dme) = setup(car)
        assertTrue(Elm327(elm).init())
        val p = dme.discover(1000)!!
        assertEquals(0x4205, p.chosen[Sig.BOOST])
        assertEquals(0x4AB0, p.chosen[Sig.BOOST_TARGET])
        assertEquals(0x4A4E, p.chosen[Sig.TIMING_5])
        assertEquals(0x4A4F, p.chosen[Sig.TIMING_6])
        assertEquals(0x4408, p.chosen[Sig.OIL])
        assertEquals(0x5805, p.chosen[Sig.COOLANT])
        assertEquals(1, p.lengths[0x580F])
        assertTrue(p.multiDid)
        assertTrue(0x4015 !in p.chosen.values) // IAT answered by 580F first, the fallback is never asked
        // Every request that reached the car was a ReadDataByIdentifier.
        assertTrue(elm.sent.filterNot { it.startsWith("AT") }.all { it.startsWith("22") })
    }

    @Test fun fallsBackToOneDidPerRequest() = runBlocking {
        val car = FakeN55(multiDid = false)
        val (_, _, dme) = setup(car)
        val p = dme.discover(1000)!!
        assertFalse(p.multiDid)
        val r = dme.read(p, listOf(Sig.TIMING_1)) as PollResult.Values
        assertEquals(30.0, r.values.getValue(Sig.TIMING_1), 1e-9)
    }

    @Test fun readsTwoValuesInOneRequest() = runBlocking {
        val car = FakeN55()
        val (_, _, dme) = setup(car)
        val p = dme.discover(1000)!!
        car.timing[0] = 12.3; car.timing[1] = -4.5
        car.requests.clear()
        val r = dme.read(p, listOf(Sig.TIMING_1, Sig.TIMING_2)) as PollResult.Values
        assertEquals(12.3, r.values.getValue(Sig.TIMING_1), 1e-9)
        assertEquals(-4.5, r.values.getValue(Sig.TIMING_2), 1e-9)
        assertEquals(listOf(listOf(0x4A49, 0x4A4A)), car.requests)
        car.boostKpa = 225.5; car.targetKpa = 230.0
        val b = dme.read(p, listOf(Sig.BOOST, Sig.BOOST_TARGET)) as PollResult.Values
        assertEquals(225.5, b.values.getValue(Sig.BOOST), 0.01)
        assertEquals(230.0, b.values.getValue(Sig.BOOST_TARGET), 0.01)
    }

    @Test fun odometerAndDmeShareTheLink() = runBlocking {
        val car = FakeN55()
        val (elm, reader, dme) = setup(car)
        val p = dme.discover(1000)!!
        assertEquals(OdoResult.Ok(98_531, "KOMBI"), reader.readOdometer("KOMBI"))
        elm.sent.clear()
        dme.read(p, listOf(Sig.BOOST, Sig.BOOST_TARGET))
        // Cluster -> engine only re-points the filter, extended address and flow control.
        assertEquals(listOf("ATCRA612", "ATCEA12", "ATFCSD12300000", "2242054AB0"), elm.sent)
    }

    @Test fun silentDmeMeansTryAgainLater() = runBlocking {
        val (_, _, dme) = setup(FakeN55())
        val silent = DmeReader(CarReader(Elm327(FakeElm({ _, _ -> null }))))
        assertNull(silent.discover(1000))
        assertTrue(dme.discover(1000) != null)
    }

    @Test fun profileSurvivesJson() = runBlocking {
        val (_, _, dme) = setup(FakeN55())
        val p = dme.discover(1000)!!.copy(channelVerdicts = mapOf(Sig.TIMING_5 to true, Sig.TIMING_6 to false))
        assertEquals(p, DmeProfile.fromJson(p.toJson()))
    }

    @Test fun refusesAnythingButReads() {
        Elm327.requireReadOnly("ATZ")
        Elm327.requireReadOnly("22D10D")
        Elm327.requireReadOnly("010C")
        for (cmd in listOf("2E3000AA", "1003", "3101FF00", "11 01", "14FFFFFF", "2F600103", "27 01", "3E00")) {
            assertThrows(IllegalArgumentException::class.java) { Elm327.requireReadOnly(cmd) }
        }
    }
}
