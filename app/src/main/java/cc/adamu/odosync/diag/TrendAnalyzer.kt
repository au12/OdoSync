package cc.adamu.odosync.diag

import java.util.Locale

/**
 * Turns a finished drive plus the drives before it into [HealthAlert]s.
 *
 * "Creeping up" is judged on pull *rate* (pull events per minute in boost) so a long highway
 * drive and a short spirited one compare fairly, and only drives with at least 20 s of boost
 * count. Three angles:
 *  - spike: this drive's rate is at least double the usual (median of the last 10 drives);
 *  - creep: the median of the last 5 drives has risen 50%+ over the 10 before them;
 *  - pattern: the same cylinder led the pulls on each of the last 3 drives.
 * Plus one-drive checks: a single big pull, underboost/overboost, hot oil, hot intake air,
 * misfires and a sagging low-pressure fuel supply.
 */
class TrendAnalyzer(
    private val cfg: HealthThresholds = HealthThresholds(),
    private val units: Units = Units.US,
) {

    /** [history] = earlier drives, oldest first, not including [trip]. */
    fun evaluate(trip: TripSummary, history: List<TripSummary>): List<HealthAlert> {
        val out = mutableListOf<HealthAlert>()
        val prior = history.filter { it.qualifies && it.startedAt < trip.startedAt }

        // ------------------------------------------------------------ single big pull
        if (trip.maxPullDeg >= cfg.severePullDeg && trip.maxPullCylinder != null) {
            val cyl = trip.maxPullCylinder
            val big = trip.events.count { it.maxDeg >= cfg.severePullDeg }
            val worst = trip.events.maxByOrNull { it.maxDeg }
            val where = worst?.let { e ->
                listOfNotNull(e.boostKpa?.let { "at ${units.fmtPressure(it)}" }, e.iatC?.let { "IAT ${units.fmtTemp(it)}" })
                    .joinToString(", ")
            }.orEmpty()
            out += HealthAlert(
                "severe_pull", Severity.ALERT,
                "Big timing pull on cylinder $cyl",
                "Cylinder $cyl ran ${Units.fmtDeg(trip.maxPullDeg)} behind the others" +
                    (if (where.isNotEmpty()) " ($where)" else "") +
                    (if (big > 1) "; $big pulls of ${Units.fmtDeg(cfg.severePullDeg)} or more this drive" else "") +
                    ". A one-off can be a bad tank of fuel. If it repeats, look at that cylinder's plug and coil.",
                cyl,
            )
        }

        // ------------------------------------------------------------ pull rate: spike + creep
        val rate = trip.pullRate
        if (trip.qualifies && rate != null) {
            var spike: HealthAlert? = null
            var creep: HealthAlert? = null
            val base = prior.takeLast(10).mapNotNull { it.pullRate }
            if (base.size >= 3) {
                val usual = TripAnalyzer.median(base)
                if (rate >= 0.5 && trip.pullEvents >= 2 && rate >= maxOf(2 * usual, usual + 0.5)) {
                    spike = HealthAlert(
                        "pull_spike", if (rate >= maxOf(3 * usual, usual + 1.5)) Severity.ALERT else Severity.WATCH,
                        "More timing pulls than usual",
                        "${fmtRate(rate)} pulls per minute in boost on this drive, against your usual ${fmtRate(usual)}.",
                    )
                }
            }
            val all = (prior + trip).mapNotNull { it.pullRate }
            if (all.size >= 10) {
                val recent = TripAnalyzer.median(all.takeLast(5))
                val before = TripAnalyzer.median(all.dropLast(5).takeLast(10))
                if (recent >= 1.5 * before && recent - before >= 0.3) {
                    creep = HealthAlert(
                        "pull_creep", Severity.WATCH,
                        "Timing pulls creeping up",
                        "Over your last 5 boosted drives the pull rate rose to ${fmtRate(recent)} per minute in boost, " +
                            "from ${fmtRate(before)} on the 10 before. Fuel quality, heat and plugs are the usual causes.",
                    )
                }
            }
            // A sustained rise says more than "this drive was high", so the creep wins over a
            // Watch-level spike; an Alert-level spike is still reported alongside it.
            if (creep != null) out += creep
            if (spike != null && (creep == null || spike.severity == Severity.ALERT)) out += spike
        }

        // ------------------------------------------------------------ same cylinder, drive after drive
        if (trip.qualifies) {
            val last3 = (prior + trip).takeLast(3)
            val leaders = last3.map { t -> leader(t) }
            val cyl = leaders.firstOrNull()
            if (last3.size == 3 && cyl != null && leaders.all { it == cyl }) {
                out += HealthAlert(
                    "cylinder_pattern", Severity.WATCH,
                    "Cylinder $cyl keeps pulling timing",
                    "Cylinder $cyl had the most timing pulls on each of your last 3 boosted drives. On an N55 " +
                        "that usually points at that cylinder's spark plug or coil (swap the coil with a neighbour " +
                        "to see if the pulls follow it), or its injector.",
                    cyl,
                )
            }
        }

        // ------------------------------------------------------------ boost against target
        if (trip.underboostEvents >= 2 || (trip.underboostEvents >= 1 && (trip.worstUnderboostKpa ?: 0.0) <= -2 * cfg.underboostKpa)) {
            out += HealthAlert(
                "underboost", Severity.WATCH,
                "Boost falling short of target",
                "Boost sat ${units.fmtPressure(cfg.underboostKpa)} or more under the DME's target for a second or " +
                    "longer ${times(trip.underboostEvents)} (worst ${units.fmtPressure(trip.worstUnderboostKpa, signed = true)}). " +
                    "Check for a boost leak (charge pipe, intercooler couplers) or a lazy wastegate.",
            )
        } else {
            val devs = prior.takeLast(10).mapNotNull { it.meanBoostDevKpa }
            val dev = trip.meanBoostDevKpa
            if (dev != null && devs.size >= 5 && trip.boostSeconds >= 30) {
                val usual = TripAnalyzer.median(devs)
                if (dev <= usual - 8) {
                    out += HealthAlert(
                        "boost_drift", Severity.WATCH,
                        "Boost tracking target worse than usual",
                        "On this drive boost averaged ${units.fmtPressure(dev, signed = true)} against target, " +
                            "versus ${units.fmtPressure(usual, signed = true)} normally. An early sign of a small boost leak.",
                    )
                }
            }
        }
        if (trip.overboostEvents >= 1) {
            out += HealthAlert(
                "overboost", Severity.WATCH,
                "Boost overshooting target",
                "Boost went ${units.fmtPressure(cfg.overboostKpa)} or more over target ${times(trip.overboostEvents)} " +
                    "(worst ${units.fmtPressure(trip.worstOverboostKpa, signed = true)}). Usually wastegate control.",
            )
        }

        // ------------------------------------------------------------ temperatures
        trip.oilMaxC?.takeIf { it >= cfg.oilAlertC }?.let { oil ->
            out += HealthAlert("oil_hot", Severity.ALERT, "Oil reached ${units.fmtTemp(oil)}",
                "Oil temperature peaked at ${units.fmtTemp(oil)} (alert set at ${units.fmtTemp(cfg.oilAlertC)}). " +
                    "Give it a cool-down drive and keep an eye on the oil level.")
        }
        trip.iatMaxInBoostC?.takeIf { it >= cfg.iatAlertC }?.let { iat ->
            out += HealthAlert("iat_hot", Severity.WATCH, "Hot intake air under boost",
                "Intake air reached ${units.fmtTemp(iat)} under boost" +
                    (trip.iatRiseMaxC?.let { ", rising up to ${units.fmtTempDelta(it)} during a run" } ?: "") +
                    ". The DME pulls timing on hot air: heat soak, or more than the intercooler can shed.")
        }

        // ------------------------------------------------------------ misfires, fuel supply
        val mis = trip.misfires.withIndex().filter { (it.value ?: 0) >= 10 }
        if (mis.isNotEmpty()) {
            val worst = mis.maxBy { it.value ?: 0 }
            out += HealthAlert("misfires", Severity.WATCH,
                "Misfires on counter ${worst.index + 1}",
                "The DME counted ${mis.joinToString { "${it.value} on counter ${it.index + 1}" }} this drive.",
                worst.index + 1)
        }
        trip.lowFuelMinInBoostBar?.takeIf { it < 3.0 }?.let { p ->
            out += HealthAlert("low_fuel_pressure", Severity.WATCH, "Low-side fuel pressure sagging",
                String.format(Locale.US, "The in-tank pump's pressure fell to %.1f bar under boost. ", p) +
                    "It normally holds 5-6 bar; a weak low-pressure pump starves the HPFP at high load.")
        }
        return out
    }

    /** The cylinder with the most pull events on [t], if it clearly led (2+ events, half or more of them). */
    private fun leader(t: TripSummary): Int? {
        val total = t.perCylEvents.sum()
        val (idx, n) = t.perCylEvents.withIndex().maxByOrNull { it.value }?.let { it.index to it.value } ?: return null
        return if (n >= 2 && n * 2 >= total) idx + 1 else null
    }

    private fun times(n: Int) = if (n == 1) "once" else "$n times"

    private fun fmtRate(r: Double) = String.format(Locale.US, "%.1f", r)
}
