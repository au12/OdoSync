package cc.adamu.odosync.upload

import cc.adamu.odosync.diag.Severity
import cc.adamu.odosync.diag.TripSummary
import cc.adamu.odosync.diag.Units
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Markdown for the Glovebox/LubeLogger notes (Glovebox renders notes with GitHub-flavoured
 * markdown, tables included). Three kinds:
 *  - a daily drive log: every drive that day as its own section;
 *  - a per-drive note, if you prefer one note per drive;
 *  - a pinned "Engine health" note, rewritten after every drive, with the status, the last
 *    drive's findings and a table of recent boosted drives.
 */
class NoteRenderer(private val u: Units, private val locale: Locale = Locale.US) {

    private val dayFmt get() = SimpleDateFormat("EEE, MMM d, yyyy", locale)
    private val shortDayFmt get() = SimpleDateFormat("MMM d", locale)
    private val timeFmt get() = SimpleDateFormat("h:mm a", locale)

    fun dayKey(t: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    fun dailyDescription(day: Long): String = "Drive log · " + dayFmt.format(Date(day))

    fun daily(trips: List<TripSummary>): String = buildString {
        val sorted = trips.sortedBy { it.startedAt }
        append("Engine health from the DME, logged by OdoSync. ")
        append("${sorted.size} drive${if (sorted.size == 1) "" else "s"}")
        val boost = sorted.sumOf { it.boostSeconds }
        if (boost > 0) append(", ${minutes(boost)} in boost")
        append(".\n\n")
        sorted.forEach { append(section(it)).append("\n") }
    }

    fun tripDescription(t: TripSummary): String =
        "Drive · " + shortDayFmt.format(Date(t.startedAt)) + ", " + timeFmt.format(Date(t.startedAt))

    fun trip(t: TripSummary): String = "Engine health from the DME, logged by OdoSync.\n\n" + section(t)

    /** One drive: a heading, its alerts, and a two-column table of readings. */
    fun section(t: TripSummary): String = buildString {
        append("### ").append(timeFmt.format(Date(t.startedAt))).append(" – ").append(timeFmt.format(Date(t.endedAt)))
        append(" · ").append(u.fmtDistance(t.distanceKm)).append("\n\n")
        for (a in t.alerts.sortedByDescending { it.severity }) {
            append(if (a.severity == Severity.ALERT) "🔴 " else "🟡 ")
            append("**").append(a.title).append("**: ").append(a.message).append("\n\n")
        }
        append("| | |\n|---|---|\n")
        rows(t).forEach { (k, v) -> append("| ").append(k).append(" | ").append(v).append(" |\n") }
    }

    fun rows(t: TripSummary): List<Pair<String, String>> = buildList {
        add("Time in boost" to if (t.boostSeconds > 0) "${minutes(t.boostSeconds)} over ${t.boostEpisodes} run${s(t.boostEpisodes)}" else "none")
        add("Timing pulls" to when {
            t.timingFramesInBoost == 0 -> "not measured (no boost)"
            t.pullEvents == 0 -> "none · cylinders ${t.cylindersMonitored.joinToString(",")} watched"
            else -> "${t.pullEvents} event${s(t.pullEvents)} · max ${Units.fmtDeg(t.maxPullDeg)} on cyl ${t.maxPullCylinder}" +
                (t.pullRate?.let { " · %.1f/min in boost".format(Locale.US, it) } ?: "")
        })
        if (t.pullEvents > 0) {
            add("Pulls by cylinder" to t.perCylEvents.withIndex()
                .filter { it.value > 0 }.joinToString(" · ") { "${it.index + 1}: ${it.value} (max ${Units.fmtDeg(t.perCylMaxPull[it.index])})" })
        }
        if (t.peakBoostKpa != null) {
            add("Boost" to buildString {
                append("peak ").append(u.fmtPressure(t.peakBoostKpa))
                t.peakTargetKpa?.let { append(" (target ").append(u.fmtPressure(it)).append(")") }
                t.meanBoostDevKpa?.let { append(" · avg ").append(u.fmtPressure(it, signed = true)).append(" vs target") }
            })
        }
        if (t.underboostEvents + t.overboostEvents > 0) {
            add("Off target" to listOfNotNull(
                t.worstUnderboostKpa?.takeIf { t.underboostEvents > 0 }?.let { "under ${t.underboostEvents}× (worst ${u.fmtPressure(it, signed = true)})" },
                t.worstOverboostKpa?.takeIf { t.overboostEvents > 0 }?.let { "over ${t.overboostEvents}× (worst ${u.fmtPressure(it, signed = true)})" },
            ).joinToString(" · "))
        }
        if (t.iatMaxC != null) {
            add("Intake air" to "peak ${u.fmtTemp(t.iatMaxC)}" +
                (t.iatRiseMaxC?.takeIf { it > 0 }?.let { " · ${u.fmtTempDelta(it)} during a run" } ?: ""))
        }
        if (t.oilMaxC != null || t.coolantMaxC != null) {
            add("Oil / coolant" to "${u.fmtTemp(t.oilMaxC)} / ${u.fmtTemp(t.coolantMaxC)}" +
                (if (t.coldBoostSeconds >= 5) " · ${t.coldBoostSeconds.roundToInt()} s of boost on cold oil" else ""))
        }
        if (t.railMinInBoostBar != null || t.lowFuelMinInBoostBar != null) {
            add("Fuel in boost" to listOfNotNull(
                t.railMinInBoostBar?.let { "rail ≥ ${Units.fmtBar(it)}" },
                t.lowFuelMinInBoostBar?.let { "low side ≥ %.1f bar".format(Locale.US, it) },
            ).joinToString(" · "))
        }
        if (t.lambdaMinInBoost != null) {
            add("Lambda in boost" to "%.2f – %.2f".format(Locale.US, t.lambdaMinInBoost, t.lambdaMaxInBoost ?: t.lambdaMinInBoost))
        }
        t.misfireTotal?.let { total ->
            add("Misfires" to if (total == 0) "none" else t.misfires.withIndex()
                .filter { (it.value ?: 0) > 0 }.joinToString(" · ") { "counter ${it.index + 1}: ${it.value}" })
        }
    }

    fun healthDescription(vehicle: String): String = "Engine health" + (if (vehicle.isNotBlank()) " · $vehicle" else "")

    /** The pinned overview note. [trips] oldest first. */
    fun health(trips: List<TripSummary>, now: Long): String = buildString {
        val last = trips.lastOrNull()
        val status = last?.severity ?: Severity.OK
        append("**Status: ").append(when (status) { Severity.OK -> "OK ✅"; Severity.WATCH -> "Watch 🟡"; Severity.ALERT -> "Alert 🔴" })
        append("** · updated ").append(shortDayFmt.format(Date(now))).append(", ").append(timeFmt.format(Date(now))).append("\n\n")
        if (last == null) {
            append("No drives logged yet.\n")
            return@buildString
        }
        if (last.alerts.isNotEmpty()) {
            append("Last drive (").append(tripDescription(last).removePrefix("Drive · ")).append("):\n\n")
            last.alerts.sortedByDescending { it.severity }.forEach { append("- **").append(it.title).append("**: ").append(it.message).append("\n") }
            append("\n")
        } else {
            append("Last drive: nothing unusual. ").append(oneLine(last)).append("\n\n")
        }

        val boosted = trips.filter { it.qualifies }.takeLast(10).reversed()
        if (boosted.isNotEmpty()) {
            append("#### Recent boosted drives\n\n")
            append("| Date | Distance | Boost | Pulls/min | Max pull | Peak boost | Peak IAT | Oil |\n")
            append("|---|---|---|---|---|---|---|---|\n")
            for (t in boosted) {
                append("| ").append(shortDayFmt.format(Date(t.startedAt)))
                append(" | ").append(u.fmtDistance(t.distanceKm))
                append(" | ").append(minutes(t.boostSeconds))
                append(" | ").append(t.pullRate?.let { "%.1f".format(Locale.US, it) } ?: "—")
                append(" | ").append(if (t.pullEvents == 0) "—" else "${Units.fmtDeg(t.maxPullDeg)} (cyl ${t.maxPullCylinder})")
                append(" | ").append(u.fmtPressure(t.peakBoostKpa))
                append(" | ").append(u.fmtTemp(t.iatMaxC))
                append(" | ").append(u.fmtTemp(t.oilMaxC))
                append(" |\n")
            }
            append("\n")
        }

        val monthAgo = now - 30L * 24 * 3600 * 1000
        val recent = trips.filter { it.startedAt >= monthAgo }
        if (recent.any { it.pullEvents > 0 }) {
            val perCyl = (0 until 6).map { i -> recent.sumOf { it.perCylEvents.getOrElse(i) { 0 } } }
            append("#### Timing pulls by cylinder, last 30 days\n\n")
            append((1..6).joinToString(" | ", "| ", " |\n") { "Cyl $it" })
            append("|---|---|---|---|---|---|\n")
            append(perCyl.joinToString(" | ", "| ", " |\n"))
            append("\n")
        }
        append("_Timing pulls are read from each cylinder's ignition angle: a cylinder running ")
        append("well behind the others under boost is knock control at work. Boost, intake air and ")
        append("oil come straight from the DME. Updated by OdoSync after every drive._\n")
    }

    private fun oneLine(t: TripSummary): String = buildString {
        append(u.fmtDistance(t.distanceKm))
        if (t.boostSeconds > 0) append(", ${minutes(t.boostSeconds)} in boost")
        t.peakBoostKpa?.let { append(", peak ${u.fmtPressure(it)}") }
        t.iatMaxC?.let { append(", IAT ${u.fmtTemp(it)}") }
        t.oilMaxC?.let { append(", oil ${u.fmtTemp(it)}") }
        append(".")
    }

    private fun s(n: Int) = if (n == 1) "" else "s"

    companion object {
        fun minutes(seconds: Double): String {
            val total = seconds.roundToInt()
            return if (total < 60) "$total s" else "${total / 60} min ${total % 60} s"
        }

        fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
