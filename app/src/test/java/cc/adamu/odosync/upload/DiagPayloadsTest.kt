package cc.adamu.odosync.upload

import cc.adamu.odosync.diag.HealthAlert
import cc.adamu.odosync.diag.PullEvent
import cc.adamu.odosync.diag.Severity
import cc.adamu.odosync.diag.TripSummary
import cc.adamu.odosync.diag.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagPayloadsTest {

    private val start = 1_763_300_000_000L

    private val trip = TripSummary(
        startedAt = start, endedAt = start + 29 * 60_000, startKm = 98_531, endKm = 98_560,
        boostSeconds = 252.0, boostEpisodes = 9, peakBoostKpa = 122.6, peakTargetKpa = 124.8,
        meanBoostDevKpa = -1.4, timingFramesInBoost = 610, pullEvents = 3, maxPullDeg = 2.3, maxPullCylinder = 5,
        perCylEvents = listOf(0, 0, 1, 0, 2, 0), perCylMaxPull = listOf(0.0, 0.0, 1.6, 0.0, 2.3, 0.0),
        cylindersMonitored = listOf(1, 2, 3, 4, 5, 6),
        events = listOf(PullEvent(412.5, listOf(5), 2.3, 118.0, 41.0, 104.0, 96.0)),
        iatMaxC = 44.0, iatMaxInBoostC = 44.0, iatRiseMaxC = 12.0, oilMaxC = 104.0, coolantMaxC = 98.0,
        railMinInBoostBar = 142.0, lowFuelMinInBoostBar = 5.6, lambdaMinInBoost = 0.86, lambdaMaxInBoost = 1.0,
        misfires = listOf(0, 0, 0, 0, 0, 0),
        alerts = listOf(HealthAlert("pull_creep", Severity.WATCH, "Timing pulls creeping up", "Over your last 5 boosted drives…")),
    )

    @Test fun driveSectionReadsWell() {
        val md = NoteRenderer(Units.US).section(trip)
        assertTrue(md, md.startsWith("### "))
        assertTrue(md, md.contains("18.0 mi")) // 29 km
        assertTrue(md, md.contains("**Timing pulls creeping up**"))
        assertTrue(md, md.contains("| Timing pulls | 3 events · max 2.3° on cyl 5 · 0.7/min in boost |"))
        assertTrue(md, md.contains("| Pulls by cylinder | 3: 1 (max 1.6°) · 5: 2 (max 2.3°) |"))
        assertTrue(md, md.contains("peak 17.8 psi (target 18.1 psi)"))
        assertTrue(md, md.contains("| Intake air | peak 111 °F · +22 °F during a run |"))
        assertTrue(md, md.contains("| Time in boost | 4 min 12 s over 9 runs |"))
        assertTrue(md, md.contains("| Misfires | none |"))
    }

    @Test fun metricUnits() {
        val md = NoteRenderer(Units.METRIC).section(trip)
        assertTrue(md, md.contains("29.0 km"))
        assertTrue(md, md.contains("peak 1.23 bar"))
        assertTrue(md, md.contains("| Oil / coolant | 104 °C / 98 °C |"))
    }

    @Test fun healthNoteHasStatusTableAndCylinders() {
        val md = NoteRenderer(Units.US).health(listOf(trip), start + 30 * 60_000)
        assertTrue(md, md.startsWith("**Status: Watch"))
        assertTrue(md, md.contains("| Date | Distance | Boost | Pulls/min | Max pull | Peak boost | Peak IAT | Oil |"))
        assertTrue(md, md.contains("| Cyl 1 | Cyl 2 | Cyl 3 | Cyl 4 | Cyl 5 | Cyl 6 |"))
        assertTrue(md, md.contains("| 0 | 0 | 1 | 0 | 2 | 0 |"))
    }

    @Test fun homeAssistantStates() {
        val states = HaPayloads.states(trip, Units.US, "odosync_435i", "2016 BMW 435i")
        val byId = states.associateBy { it.entityId }
        assertEquals("watch", byId.getValue("sensor.odosync_435i_engine_health").state)
        assertEquals("2.3", byId.getValue("sensor.odosync_435i_timing_pull_max").state)
        assertEquals(5, byId.getValue("sensor.odosync_435i_timing_pull_max").attributes.getInt("cylinder"))
        assertEquals("0.71", byId.getValue("sensor.odosync_435i_timing_pull_rate").state)
        val boost = byId.getValue("sensor.odosync_435i_boost_peak")
        assertEquals("17.8", boost.state)
        assertEquals("psi", boost.attributes.getString("unit_of_measurement"))
        assertEquals("pressure", boost.attributes.getString("device_class"))
        assertEquals("219", byId.getValue("sensor.odosync_435i_oil_temp_peak").state) // 104 °C
        assertEquals("2016 BMW 435i peak oil temp", byId.getValue("sensor.odosync_435i_oil_temp_peak").attributes.getString("friendly_name"))
        assertEquals("0", byId.getValue("sensor.odosync_435i_misfires").state)
    }

    @Test fun alertEventOnlyWhenSomethingToSay() {
        val e = HaPayloads.alertEvent(trip, "435i")!!
        assertEquals("watch", e.getString("severity"))
        assertEquals("Timing pulls creeping up", e.getString("title"))
        assertNull(HaPayloads.alertEvent(trip.copy(alerts = emptyList()), "435i"))
        val summary = HaPayloads.summaryEvent(trip, Units.METRIC, "435i")
        assertEquals("29.0", summary.getString("distance"))
        assertEquals("km", summary.getString("distance_unit"))
        assertFalse(summary.has("nonsense"))
    }
}
