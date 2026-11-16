package cc.adamu.odosync.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAnalyzerTest {

    /** Drives a [TripAnalyzer] the way the sampler does: boost poll, then the three timing pairs. */
    private class Drive(val a: TripAnalyzer, var t: Long = 0) {
        var baro = 100.0
        var boost = 100.0
        var target = 100.0
        var iat = 30.0
        var oil = 95.0
        val timing = DoubleArray(6) { 30.0 }

        fun step(ms: Long = 400, extra: Map<Sig, Double> = emptyMap()) {
            t += ms / 4
            a.onValues(t, mapOf(Sig.BOOST to boost, Sig.BOOST_TARGET to target, Sig.BARO to baro, Sig.IAT to iat, Sig.OIL to oil) + extra)
            for (pair in listOf(0 to 1, 2 to 3, 4 to 5)) {
                t += ms / 4
                a.onValues(t, mapOf(Sig.TIMING[pair.first] to timing[pair.first], Sig.TIMING[pair.second] to timing[pair.second]))
            }
        }

        fun steps(n: Int, ms: Long = 400, each: (Int) -> Unit = {}) = repeat(n) { each(it); step(ms) }
    }

    private val trusted = mapOf(Sig.TIMING_5 to true, Sig.TIMING_6 to true)

    @Test fun cruiseHasNoBoostAndNoPulls() {
        val d = Drive(TripAnalyzer(0, initialVerdicts = trusted))
        d.steps(100) { i -> d.timing.fill(30.0 + (i % 5)) }
        val s = d.a.finish(d.t)
        assertEquals(0.0, s.boostSeconds, 1e-9)
        assertEquals(0, s.pullEvents)
        assertEquals(0, s.timingFramesInBoost)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), s.cylindersMonitored)
    }

    @Test fun singleCylinderPullUnderBoost() {
        val d = Drive(TripAnalyzer(0, initialVerdicts = trusted))
        d.steps(10)
        // A 10 s pull at ~1 bar with cylinder 5 knocking for about a second.
        d.target = 205.0; d.boost = 202.0
        d.steps(25) { i ->
            d.timing.fill(12.0)
            if (i in 10..12) d.timing[4] = 8.6 // 3.4° behind
            d.iat = 30.0 + i * 0.5
        }
        d.boost = 100.0; d.target = 100.0
        d.steps(10)
        val s = d.a.finish(d.t)
        assertEquals(1, s.boostEpisodes)
        assertEquals(10.0, s.boostSeconds, 0.6)
        assertEquals(1, s.pullEvents)
        assertEquals(5, s.maxPullCylinder)
        assertEquals(3.4, s.maxPullDeg, 1e-9)
        assertEquals(listOf(0, 0, 0, 0, 1, 0), s.perCylEvents)
        assertEquals(listOf(5), s.events.single().cylinders)
        assertEquals(102.0, s.events.single().boostKpa!!, 1e-9)
        assertEquals(102.0, s.peakBoostKpa!!, 1e-9)
        assertEquals(105.0, s.peakTargetKpa!!, 1e-9)
        assertEquals(12.0, s.meanTimingInBoost!!, 0.5) // the first read into boost still sees cruise timing
        assertNotNull(s.iatRiseMaxC)
        assertTrue(s.iatRiseMaxC!! >= 10.0)
        assertEquals(0, s.underboostEvents)
    }

    @Test fun pullsOnSeveralCylindersMergeIntoOneEvent() {
        val d = Drive(TripAnalyzer(0, initialVerdicts = trusted))
        d.target = 220.0; d.boost = 218.0
        d.steps(20) { i ->
            d.timing.fill(10.0)
            if (i in 5..7) { d.timing[0] = 7.5; d.timing[2] = 8.0 } // bad fuel: two cylinders at once
        }
        val s = d.a.finish(d.t)
        assertEquals(1, s.pullEvents)
        assertEquals(listOf(1, 3), s.events.single().cylinders)
        assertEquals(listOf(1, 0, 1, 0, 0, 0), s.perCylEvents)
    }

    @Test fun smallWobbleIsNotAPull() {
        val d = Drive(TripAnalyzer(0, initialVerdicts = trusted))
        d.target = 220.0; d.boost = 218.0
        d.steps(30) { i -> d.timing.fill(10.0); d.timing[i % 6] = 9.0 } // 1.0° < 1.5° threshold
        assertEquals(0, d.a.finish(d.t).pullEvents)
    }

    @Test fun shiftRetardCaughtMidRoundIsNotAPull() {
        // An upshift: the gearbox asks for torque reduction and every cylinder loses ~8° for a
        // moment. One read lands inside it (cylinders 3/4), the reads either side don't.
        val a = TripAnalyzer(0, initialVerdicts = trusted)
        var t = 0L
        fun read(vararg pairs: Pair<Sig, Double>) { t += 100; a.onValues(t, mapOf(*pairs)) }
        fun round(c12: Double, c34: Double, c56: Double) {
            read(Sig.BOOST to 220.0, Sig.BOOST_TARGET to 222.0, Sig.BARO to 100.0)
            read(Sig.TIMING_1 to c12, Sig.TIMING_2 to c12)
            read(Sig.TIMING_3 to c34, Sig.TIMING_4 to c34)
            read(Sig.TIMING_5 to c56, Sig.TIMING_6 to c56)
        }
        repeat(5) { round(10.0, 10.0, 10.0) }
        round(10.0, 2.0, 10.0) // cylinders 3 and 4 read mid-shift: both equally low
        repeat(5) { round(10.0, 10.0, 10.0) }
        assertEquals(0, a.finish(t).pullEvents)
    }

    @Test fun pullWithoutSameInstantEvidenceNeedsTwoReads() {
        // One DID per request (no partner in the reply): a single low read isn't enough...
        val a = TripAnalyzer(0, initialVerdicts = trusted)
        var t = 0L
        fun read(vararg pairs: Pair<Sig, Double>) { t += 100; a.onValues(t, mapOf(*pairs)) }
        fun round(c5: Double) {
            read(Sig.BOOST to 220.0, Sig.BOOST_TARGET to 222.0, Sig.BARO to 100.0)
            for (i in 0 until 6) read(Sig.TIMING[i] to if (i == 4) c5 else 10.0)
        }
        repeat(3) { round(10.0) }
        round(7.0)
        repeat(3) { round(10.0) }
        assertEquals(0, a.finish(t).pullEvents)
        // ...but the same pull on two reads in a row is.
        val b = TripAnalyzer(0, initialVerdicts = trusted)
        t = 0
        fun readB(vararg pairs: Pair<Sig, Double>) { t += 100; b.onValues(t, mapOf(*pairs)) }
        fun roundB(c5: Double) {
            readB(Sig.BOOST to 220.0, Sig.BOOST_TARGET to 222.0, Sig.BARO to 100.0)
            for (i in 0 until 6) readB(Sig.TIMING[i] to if (i == 4) c5 else 10.0)
        }
        repeat(3) { roundB(10.0) }
        repeat(2) { roundB(7.0) }
        repeat(3) { roundB(10.0) }
        val s = b.finish(t)
        assertEquals(1, s.pullEvents)
        assertEquals(5, s.maxPullCylinder)
    }

    @Test fun underboostIsCountedOnlyOnceSpooled() {
        val d = Drive(TripAnalyzer(0, initialVerdicts = trusted))
        d.target = 220.0; d.boost = 140.0 // spooling: way under, but just requested
        d.step()
        d.boost = 215.0
        d.steps(5)
        d.boost = 195.0 // 25 kPa (3.6 psi) short, sustained
        d.steps(6)
        d.boost = 218.0
        d.steps(3)
        val s = d.a.finish(d.t)
        assertEquals(1, s.underboostEvents)
        assertEquals(-25.0, s.worstUnderboostKpa!!, 1e-9)
        assertEquals(0, s.overboostEvents)
        assertTrue(s.meanBoostDevKpa!! < 0)
    }

    @Test fun inferredCylindersNeedConfirmation() {
        val d = Drive(TripAnalyzer(0))
        // Cylinder 6's DID isn't timing at all: it sits at 0 whatever the engine does.
        d.steps(80) { i -> d.timing.fill(5.0 + (i % 20)); d.timing[5] = 0.0 }
        d.target = 220.0; d.boost = 218.0
        d.steps(10) { d.timing.fill(10.0); d.timing[5] = 0.0 }
        val s = d.a.finish(d.t)
        val v = d.a.channelVerdicts()
        assertEquals(true, v[Sig.TIMING_5])
        assertEquals(false, v[Sig.TIMING_6])
        assertEquals(listOf(1, 2, 3, 4, 5), s.cylindersMonitored)
        assertEquals(0, s.pullEvents) // cylinder 6 "10° behind" was never believed
    }

    @Test fun staleChannelDoesNotStallFrames() {
        val a = TripAnalyzer(0, initialVerdicts = trusted)
        var t = 0L
        a.onValues(t, mapOf(Sig.BOOST to 220.0, Sig.BOOST_TARGET to 222.0, Sig.BARO to 100.0))
        // Cylinders 5/6 answer once, then stop.
        a.onValues(t + 10, Sig.TIMING.associateWith { 10.0 })
        repeat(30) {
            t += 400
            a.onValues(t, mapOf(Sig.BOOST to 220.0, Sig.BOOST_TARGET to 222.0))
            a.onValues(t + 100, mapOf(Sig.TIMING_1 to 10.0, Sig.TIMING_2 to 10.0))
            a.onValues(t + 200, mapOf(Sig.TIMING_3 to 6.0, Sig.TIMING_4 to 10.0))
        }
        val s = a.finish(t + 300)
        assertEquals(1, s.pullEvents)
        assertEquals(3, s.maxPullCylinder)
    }

    @Test fun temperaturesAndMisfires() {
        val a = TripAnalyzer(0)
        a.onValues(0, mapOf(Sig.OIL to 60.0, Sig.COOLANT to 80.0, Sig.MISFIRE_1 to 4.0, Sig.MISFIRE_5 to 0.0))
        a.onValues(60_000, mapOf(Sig.OIL to 121.0, Sig.COOLANT to 104.0))
        a.onValues(90_000, mapOf(Sig.OIL to 125.0, Sig.MISFIRE_1 to 9.0, Sig.MISFIRE_5 to 2.0))
        val s = a.finish(100_000)
        assertEquals(125.0, s.oilMaxC!!, 1e-9)
        assertEquals(104.0, s.coolantMaxC!!, 1e-9)
        assertEquals(30.0, s.oilHotSeconds, 1e-9)
        assertEquals(listOf(5, null, null, null, 2, null), s.misfires)
        assertEquals(7, s.misfireTotal)
        assertNull(s.peakBoostKpa)
    }

    @Test fun summaryRoundTripsThroughJson() {
        val d = Drive(TripAnalyzer(1_700_000_000_000, initialVerdicts = trusted), t = 1_700_000_000_000)
        d.target = 205.0; d.boost = 202.0
        d.steps(30) { i -> d.timing.fill(12.0); if (i == 5) d.timing[2] = 8.0 }
        val s = d.a.finish(d.t, endKm = 98_560).copy(
            alerts = listOf(HealthAlert("severe_pull", Severity.ALERT, "Big timing pull on cylinder 3", "…", 3)))
        assertEquals(s, TripSummary.fromJson(s.toJson()))
    }
}
