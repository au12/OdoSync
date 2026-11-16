package cc.adamu.odosync.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendAnalyzerTest {

    private var clock = 1_700_000_000_000L

    /** A boosted drive with [pulls] events over [boostMin] minutes in boost. */
    private fun drive(
        pulls: Int, boostMin: Double = 2.0, maxPull: Double = if (pulls > 0) 2.0 else 0.0,
        perCyl: List<Int> = List(6) { if (it == 0) pulls else 0 },
        underboost: Int = 0, worstUnder: Double? = null, devMean: Double? = -2.0,
        oil: Double = 105.0, iatBoost: Double = 45.0,
    ): TripSummary {
        clock += 86_400_000
        val maxCyl = if (pulls > 0) perCyl.withIndex().maxBy { it.value }.index + 1 else null
        return TripSummary(
            startedAt = clock, endedAt = clock + 1_800_000,
            boostSeconds = boostMin * 60, boostEpisodes = 5, timingFramesInBoost = (boostMin * 150).toInt(),
            pullEvents = pulls, maxPullDeg = maxPull, maxPullCylinder = maxCyl, perCylEvents = perCyl,
            perCylMaxPull = perCyl.map { if (it > 0) maxPull else 0.0 },
            underboostEvents = underboost, worstUnderboostKpa = worstUnder, meanBoostDevKpa = devMean,
            oilMaxC = oil, iatMaxInBoostC = iatBoost, peakBoostKpa = 110.0,
        )
    }

    private val trend = TrendAnalyzer()

    private fun kinds(t: TripSummary, history: List<TripSummary>) = trend.evaluate(t, history).map { it.kind }

    @Test fun quietDriveRaisesNothing() {
        val history = List(10) { drive(pulls = 1) }
        assertEquals(emptyList<String>(), kinds(drive(pulls = 1), history))
    }

    @Test fun spikeAgainstUsualRate() {
        val history = List(10) { drive(pulls = 1) } // 0.5 per boost-minute
        val alerts = trend.evaluate(drive(pulls = 6), history) // 3.0 per boost-minute
        val spike = alerts.single { it.kind == "pull_spike" }
        assertEquals(Severity.ALERT, spike.severity)
        assertTrue(spike.message.contains("3.0") && spike.message.contains("0.5"))
    }

    @Test fun needsThreeDrivesOfHistoryBeforeJudgingRate() {
        val history = List(2) { drive(pulls = 0) }
        assertTrue("pull_spike" !in kinds(drive(pulls = 6), history))
    }

    @Test fun slowCreepOverFiveDrives() {
        // Ten drives at 0.5/min, then four at 1.0/min; the fifth (this one) completes the shift.
        val history = List(10) { drive(pulls = 1) } + List(4) { drive(pulls = 2) }
        val k = kinds(drive(pulls = 2), history)
        assertTrue("pull_creep" in k)
        assertTrue("pull_spike" !in k) // the sustained rise is the better message than "this drive was high"
    }

    @Test fun sameCylinderThreeDrivesRunning() {
        val cyl5 = List(6) { if (it == 4) 3 else 0 }
        val history = List(5) { drive(pulls = 1) } + List(2) { drive(pulls = 3, perCyl = cyl5) }
        val a = trend.evaluate(drive(pulls = 3, perCyl = cyl5), history).single { it.kind == "cylinder_pattern" }
        assertEquals(5, a.cylinder)
    }

    @Test fun bigPullIsAnAlertOnItsOwn() {
        val a = trend.evaluate(drive(pulls = 1, maxPull = 4.2), emptyList()).single()
        assertEquals("severe_pull", a.kind)
        assertEquals(Severity.ALERT, a.severity)
        assertTrue(a.message.contains("4.2°"))
    }

    @Test fun boostOilAndIntakeAir() {
        val k = kinds(drive(pulls = 0, underboost = 2, worstUnder = -28.0, oil = 132.0, iatBoost = 70.0), emptyList())
        assertEquals(setOf("underboost", "oil_hot", "iat_hot"), k.toSet())
    }

    @Test fun boostDriftAgainstUsual() {
        val history = List(8) { drive(pulls = 0, devMean = -2.0) }
        assertTrue("boost_drift" in kinds(drive(pulls = 0, devMean = -12.0), history))
        assertTrue("boost_drift" !in kinds(drive(pulls = 0, devMean = -6.0), history))
    }

    @Test fun messagesUseChosenUnits() {
        val us = TrendAnalyzer(units = Units.US).evaluate(drive(pulls = 0, underboost = 2, worstUnder = -28.0), emptyList()).single()
        assertTrue(us.message, us.message.contains("psi"))
        val metric = TrendAnalyzer(units = Units.METRIC).evaluate(drive(pulls = 0, oil = 140.0), emptyList()).single()
        assertTrue(metric.title, metric.title.contains("140 °C"))
    }
}
