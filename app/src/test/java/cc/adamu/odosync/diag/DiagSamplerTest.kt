package cc.adamu.odosync.diag

import cc.adamu.odosync.obd.CarReader
import cc.adamu.odosync.obd.Elm327
import cc.adamu.odosync.obd.FakeElm
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The sampler end to end: real Elm327/CarReader/DmeReader against the in-memory ELM327 + DME. */
class DiagSamplerTest {

    private fun rig(car: FakeN55, verdicts: Map<Sig, Boolean> = mapOf(Sig.TIMING_5 to true, Sig.TIMING_6 to true)):
        Triple<FakeElm, DiagSampler, TripCsvLog> = runBlocking {
        val elm = FakeElm(car::respond, delayMs = 3)
        val dme = DmeReader(CarReader(Elm327(elm)))
        val profile = dme.discover(0)!!.copy(channelVerdicts = verdicts)
        val t0 = System.currentTimeMillis()
        val csv = TripCsvLog(File.createTempFile("drive", ".csv").apply { deleteOnExit() }, t0).apply { open() }
        val analyzer = TripAnalyzer(t0, initialVerdicts = profile.channelVerdicts)
        Triple(elm, DiagSampler(dme, elm, profile, analyzer, csv, fastAllowed = true), csv)
    }

    @Test fun catchesAPullUnderBoostAtFastPace() = runBlocking {
        val car = FakeN55().apply {
            baroKpa = 100.0; targetKpa = 225.0; boostKpa = 221.0
            timing.fill(10.0); timing[2] = 6.0 // cylinder 3 four degrees behind
        }
        val (elm, sampler, csv) = rig(car)
        val wake = Channel<Unit>(Channel.CONFLATED)
        car.requests.clear()
        val woke = sampler.runFor(1_500, wake)
        csv.close()
        val s = sampler.analyzer.finish(System.currentTimeMillis())
        assertEquals(false, woke)
        assertTrue(sampler.fast)
        assertEquals(listOf(true), elm.fastModeCalls)
        assertTrue("polls=${s.polls}", s.polls > 40)
        assertEquals(1, s.pullEvents)
        assertEquals(3, s.maxPullCylinder)
        assertEquals(4.0, s.maxPullDeg, 1e-9)
        assertEquals(121.0, s.peakBoostKpa!!, 0.01)
        assertTrue(s.timingFramesInBoost > 5)
        // Boost and all three timing pairs dominate the requests under boost.
        val core = setOf(listOf(0x4205, 0x4AB0), listOf(0x4A49, 0x4A4A), listOf(0x4A4C, 0x4A4D), listOf(0x4A4E, 0x4A4F))
        assertTrue(car.requests.count { it in core } * 10 >= car.requests.size * 8)
        val lines = csv.file.readLines()
        assertTrue(lines.first().startsWith("time_s,boost_kPa,boost_target_kPa"))
        assertTrue(lines.size > 5)
    }

    @Test fun cruisesGently() = runBlocking {
        val car = FakeN55().apply { baroKpa = 100.0; targetKpa = 100.0; boostKpa = 99.0 }
        val (elm, sampler, _) = rig(car)
        val wake = Channel<Unit>(Channel.CONFLATED)
        car.requests.clear()
        sampler.runFor(2_300, wake)
        // First pass reads everything once (11 groups in pairs = 11 requests), then only boost each second.
        assertTrue("requests=${car.requests.size}", car.requests.size in 11..15)
        assertTrue(elm.fastModeCalls.isEmpty())
        val s = sampler.analyzer.finish(System.currentTimeMillis())
        assertEquals(0.0, s.boostSeconds, 1e-9)
        assertEquals(listOf(0, 0, 0, 0, 0, 0), s.misfires)
    }

    @Test fun wakeEndsTheSliceEarly() = runBlocking {
        val (_, sampler, _) = rig(FakeN55())
        val wake = Channel<Unit>(Channel.CONFLATED)
        wake.trySend(Unit)
        val t = System.currentTimeMillis()
        assertTrue(sampler.runFor(10_000, wake))
        assertTrue(System.currentTimeMillis() - t < 1_000)
    }
}
