package cc.adamu.odosync.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.adamu.odosync.diag.DiagStore
import cc.adamu.odosync.diag.HealthAlert
import cc.adamu.odosync.diag.LiveDiag
import cc.adamu.odosync.diag.LiveHealth
import cc.adamu.odosync.diag.Severity
import cc.adamu.odosync.diag.Sig
import cc.adamu.odosync.diag.TripAnalyzer
import cc.adamu.odosync.diag.TripSummary
import cc.adamu.odosync.diag.Units
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.upload.NoteRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Engine health: status, live view while driving, the last drive, trends and per-cylinder pulls. */
@Composable
fun HealthScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val s by Settings.get(ctx).flow.collectAsStateWithLifecycle()
    val trips by DiagStore.get(ctx).trips.collectAsStateWithLifecycle()
    val live by LiveDiag.flow.collectAsStateWithLifecycle()
    val now = rememberNow()
    val u = s.healthUnits
    val last = trips.lastOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PaddingValues(16.dp, 8.dp, 16.dp, 24.dp)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusHero(last, s.healthEnabled, now, u, onOpenSettings)
        if (live.active) LiveCard(live, u)
        if (last != null && last.alerts.isNotEmpty()) AlertsCard(last.alerts)
        if (last != null) LastDriveCard(ctx, last, u)
        val boosted = trips.filter { it.qualifies }
        if (boosted.isNotEmpty()) TrendCard(boosted.takeLast(20))
        val month = trips.filter { it.startedAt >= now - 30L * 24 * 3600 * 1000 }
        if (month.any { it.pullEvents > 0 }) CylinderCard(month)
        if (trips.size > 1) HistoryCard(trips.takeLast(15).reversed(), u)
    }
}

@Composable
private fun StatusHero(last: TripSummary?, enabled: Boolean, now: Long, u: Units, onOpenSettings: () -> Unit) {
    val sev = last?.severity
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MonitorHeart, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("ENGINE HEALTH", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (last != null) {
                    StatusPill(relativeTime(last.endedAt, now), severityTone(sev ?: Severity.OK),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                }
            }
            Spacer(Modifier.size(12.dp))
            val headline = when {
                !enabled -> "Off"
                sev == null -> "Waiting"
                sev == Severity.OK -> "All good"
                sev == Severity.WATCH -> "Worth a look"
                else -> "Needs attention"
            }
            Text(headline, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            Text(
                when {
                    !enabled -> "Engine monitoring is switched off."
                    last == null -> "Appears after your first drive with the monitor on."
                    last.alerts.isEmpty() -> "Nothing unusual on the last drive · " + NoteRenderer.minutes(last.boostSeconds) + " in boost"
                    else -> last.alerts.sortedByDescending { it.severity }.first().title +
                        if (last.alerts.size > 1) " · +${last.alerts.size - 1} more" else ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            if (last != null) {
                Spacer(Modifier.size(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    HeroStat("Pulls", if (last.timingFramesInBoost == 0) "—" else last.pullEvents.toString())
                    HeroStat("Max pull", if (last.pullEvents == 0) "—" else Units.fmtDeg(last.maxPullDeg))
                    HeroStat("Peak boost", u.fmtPressure(last.peakBoostKpa))
                    HeroStat("Oil", u.fmtTemp(last.oilMaxC))
                }
            }
            if (!enabled) {
                Spacer(Modifier.size(12.dp))
                FilledTonalButton(onClick = onOpenSettings) { Text("Turn it on") }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

@Composable
private fun LiveCard(live: LiveHealth, u: Units) {
    Section("Live", icon = Icons.Outlined.Sensors,
        subtitle = when {
            live.learning || (live.values.isEmpty() && live.note.isNotEmpty()) -> live.note
            live.fast -> "Under boost · %.1f reads/s".format(Locale.US, live.pollsPerSecond)
            else -> "Cruising · %.1f reads/s".format(Locale.US, live.pollsPerSecond)
        },
        trailing = { if (live.inBoost) StatusPill("Boost", Tone.BUSY) }) {
        if (live.learning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Section
        }
        if (live.values.isEmpty()) {
            Hint("No engine data on this drive yet. The odometer sync carries on regardless.")
            return@Section
        }
        val baro = live.values[Sig.BARO] ?: 101.3
        val boost = live.values[Sig.BOOST]?.minus(baro)
        val target = live.values[Sig.BOOST_TARGET]?.minus(baro)
        Text("Boost ${u.fmtPressure(boost?.coerceAtLeast(0.0))}  ·  target ${u.fmtPressure(target?.coerceAtLeast(0.0))}",
            style = MaterialTheme.typography.bodyMedium)
        val scale = 180.0 // kPa full scale (~26 psi)
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(((boost ?: 0.0) / scale).coerceIn(0.0, 1.0).toFloat()).fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary))
        }
        Text("Timing pull by cylinder (degrees behind the others)", style = MaterialTheme.typography.labelLarge)
        CylinderBars(live.pullNow.map { it }, maxValue = 4.0, labelFor = { v -> if (v == null) "—" else "%.1f°".format(Locale.US, v) })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Hint("IAT ${u.fmtTemp(live.values[Sig.IAT])}")
            Hint("Oil ${u.fmtTemp(live.values[Sig.OIL])}")
            Hint("Coolant ${u.fmtTemp(live.values[Sig.COOLANT])}")
        }
        Hint("This drive: ${live.pullEvents} timing pull${if (live.pullEvents == 1) "" else "s"}" +
            (if (live.maxPullDeg > 0) ", max ${Units.fmtDeg(live.maxPullDeg)}" else "") +
            " · ${NoteRenderer.minutes(live.boostSeconds)} in boost")
    }
}

/** Six labelled bars; null = cylinder not monitored. */
@Composable
private fun CylinderBars(values: List<Double?>, maxValue: Double, labelFor: (Double?) -> String) {
    val warn = toneColor(Tone.BUSY)
    val bad = toneColor(Tone.BAD)
    val ok = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(labelFor(v), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.BottomCenter) {
                    if (v != null && v > 0) {
                        val frac = (v / maxValue).coerceIn(0.04, 1.0).toFloat()
                        Box(Modifier.fillMaxWidth().fillMaxHeight(frac).background(
                            when { v >= maxValue * 0.75 -> bad; v >= maxValue * 0.375 -> warn; else -> ok }))
                    }
                }
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AlertsCard(alerts: List<HealthAlert>) {
    Section("Findings", icon = Icons.Outlined.WarningAmber, subtitle = "From your last drive") {
        alerts.sortedByDescending { it.severity }.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.Top) {
                Icon(if (a.severity == Severity.ALERT) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber, null,
                    tint = severityColor(a.severity), modifier = Modifier.size(20.dp).padding(top = 2.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(a.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LastDriveCard(ctx: Context, t: TripSummary, u: Units) {
    val fmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault())
    Section("Last drive", icon = Icons.Outlined.Speed,
        subtitle = fmt.format(Date(t.startedAt)) + " · " + u.fmtDistance(t.distanceKm) + " · " + NoteRenderer.minutes(t.durationS)) {
        NoteRenderer(u).rows(t).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(132.dp))
                Text(v, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
            }
        }
        val log = t.logFile?.let { DiagStore.get(ctx).logFile(it) }?.takeIf { it.exists() }
        if (log != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedButton(onClick = { shareFile(ctx, log) }) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share data log (CSV)")
            }
        }
    }
}

@Composable
private fun TrendCard(trips: List<TripSummary>) {
    val rates = trips.map { it.pullRate ?: 0.0 }
    val usual = TripAnalyzer.median(rates)
    Section("Pull rate trend", icon = Icons.Outlined.BarChart,
        subtitle = "Pulls per minute in boost · last ${trips.size} boosted drive${if (trips.size == 1) "" else "s"}") {
        val bar = MaterialTheme.colorScheme.primary
        val hi = toneColor(Tone.BUSY)
        val bad = toneColor(Tone.BAD)
        val grid = MaterialTheme.colorScheme.outlineVariant
        val top = max(1.0, (rates.maxOrNull() ?: 0.0) * 1.15)
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = trips.size
            val slot = size.width / n
            val bw = slot * 0.62f
            trips.forEachIndexed { i, t ->
                val r = rates[i]
                val h = (r / top * size.height).toFloat().coerceAtLeast(2f)
                val color = when (t.severity) { Severity.ALERT -> bad; Severity.WATCH -> hi; else -> bar }
                drawRoundRect(color, topLeft = Offset(i * slot + (slot - bw) / 2, size.height - h),
                    size = Size(bw, h), cornerRadius = CornerRadius(4f, 4f))
            }
            val y = (size.height - usual / top * size.height).toFloat()
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
        }
        Hint("Dashed line: your usual (%.1f/min). Amber and red bars are drives that raised a finding.".format(Locale.US, usual))
    }
}

@Composable
private fun CylinderCard(trips: List<TripSummary>) {
    val counts = (0 until 6).map { i -> trips.sumOf { it.perCylEvents.getOrElse(i) { 0 } }.toDouble() }
    val monitored = trips.flatMap { it.cylindersMonitored }.toSet()
    Section("Pulls by cylinder", icon = Icons.Outlined.BarChart, subtitle = "Timing pull events, last 30 days") {
        CylinderBars(counts.mapIndexed { i, c -> if (i + 1 in monitored) c else null },
            maxValue = max(4.0, counts.maxOrNull() ?: 0.0), labelFor = { v -> v?.toInt()?.toString() ?: "—" })
        if (5 !in monitored || 6 !in monitored) {
            Hint("Cylinders 5 and 6 are added once a drive confirms their timing channels track cylinders 1-4.")
        }
    }
}

@Composable
private fun HistoryCard(trips: List<TripSummary>, u: Units) {
    val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    Section("Recent drives", icon = Icons.Outlined.History) {
        trips.forEachIndexed { i, t ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(severityTone(t.severity))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(fmt.format(Date(t.startedAt)) + " · " + u.fmtDistance(t.distanceKm),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (t.timingFramesInBoost == 0) "no boost" else
                            "${t.pullEvents} pull${if (t.pullEvents == 1) "" else "s"}" +
                                (if (t.pullEvents > 0) " (max ${Units.fmtDeg(t.maxPullDeg)})" else "")) +
                            " · peak ${u.fmtPressure(t.peakBoostKpa)} · oil ${u.fmtTemp(t.oilMaxC)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (t.alerts.isEmpty()) Icon(Icons.Outlined.CheckCircle, null, tint = toneColor(Tone.GOOD), modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun severityTone(s: Severity) = when (s) { Severity.OK -> Tone.GOOD; Severity.WATCH -> Tone.BUSY; Severity.ALERT -> Tone.BAD }

@Composable
private fun severityColor(s: Severity): Color = toneColor(severityTone(s))

private fun shareFile(context: Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share data log"))
}
