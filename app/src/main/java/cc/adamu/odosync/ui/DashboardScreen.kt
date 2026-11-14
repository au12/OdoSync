package cc.adamu.odosync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.adamu.odosync.data.DongleState
import cc.adamu.odosync.data.LiveStatus
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.data.SettingsSnapshot
import cc.adamu.odosync.data.UploadUnit
import kotlin.math.roundToLong
import cc.adamu.odosync.data.TripPhase
import cc.adamu.odosync.data.TripStore
import cc.adamu.odosync.service.TripService
import cc.adamu.odosync.upload.UploadWorker
import java.text.NumberFormat

@Composable
fun DashboardScreen(activity: MainActivity, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val s by Settings.get(ctx).flow.collectAsStateWithLifecycle()
    val trip by TripStore.get(ctx).flow.collectAsStateWithLifecycle()
    val live by LiveStatus.flow.collectAsStateWithLifecycle()
    val perms by activity.permissionsGranted
    val now = rememberNow()
    val nf = remember { NumberFormat.getIntegerInstance() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PaddingValues(16.dp, 8.dp, 16.dp, 24.dp)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---------------------------------------------------------------- hero
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Speed, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("ODOMETER", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TripPill(live.phase, live.serviceRunning, live.tripStartKm, trip.lastKm, s.uploadUnit)
                }
                Spacer(Modifier.size(14.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (trip.lastKm > 0) nf.format(s.toUploadValue(trip.lastKm)) else "—", style = OdometerStyle)
                    Spacer(Modifier.width(8.dp))
                    Text(s.uploadUnit.label, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                Text(
                    if (trip.lastKm > 0) "${nf.format(trip.lastKm)} km · ${sourceName(trip.lastSource)} · ${relativeTime(trip.lastKmAt, now)}"
                    else "Appears after your first drive",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = s.dongleMac != null && perms,
                        onClick = { TripService.send(ctx, TripService.ACTION_READ_NOW) },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Read now")
                    }
                    OutlinedButton(
                        enabled = trip.pendingKm > 0 && s.lubeLoggerConfigured,
                        onClick = { UploadWorker.enqueue(ctx, "manual") },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Upload")
                    }
                }
            }
        }

        // ---------------------------------------------------------------- setup checklist
        val steps = listOf(
            "Allow Nearby devices" to perms,
            "Connect LubeLogger and pick the car" to s.lubeLoggerConfigured,
            "Pair the OBD dongle" to (s.dongleMac != null),
            "Choose the car's Bluetooth (recommended)" to s.carBtMacs.isNotEmpty(),
        )
        if (steps.any { !it.second }) {
            Section("Finish setup", subtitle = "${steps.count { it.second }} of ${steps.size} done") {
                steps.forEach { (label, done) ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !done) {
                            if (!perms && label.startsWith("Allow")) activity.requestPermissions() else onOpenSettings()
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                            tint = if (done) toneColor(Tone.GOOD) else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        if (!done) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                            tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- car link
        Section("Car link", icon = Icons.Outlined.Link) {
            val (dongleText, dongleTone) = when {
                s.dongleMac == null -> "Not paired" to Tone.IDLE
                live.dongle == DongleState.CONNECTED -> "Connected" to Tone.GOOD
                live.dongle == DongleState.ERROR -> "Problem" to Tone.BAD
                live.dongle in listOf(DongleState.CONNECTING, DongleState.WAITING, DongleState.INITIALISING) ->
                    live.dongle.label.removeSuffix("…") to Tone.BUSY
                else -> "Sleeping" to Tone.IDLE
            }
            StatusRow(Icons.Outlined.Sensors, "OBD dongle", dongleText, dongleTone)
            StatusRow(Icons.Outlined.DirectionsCar, "Android Auto",
                when { !s.useAndroidAuto -> "Ignored"; live.androidAuto -> "Projecting"; else -> "Off" },
                if (live.androidAuto && s.useAndroidAuto) Tone.GOOD else Tone.IDLE)
            StatusRow(Icons.Outlined.Bluetooth, "Car Bluetooth",
                when { s.carBtMacs.isEmpty() -> "Not set"; live.carBluetooth -> "Connected"; else -> "Off" },
                if (live.carBluetooth) Tone.GOOD else Tone.IDLE)
            StatusRow(Icons.Outlined.BatteryChargingFull, "Battery",
                live.voltage?.let { "%.1f V".format(it) } ?: "—")
            if (live.phase == TripPhase.ACTIVE && live.engineRpm != null) {
                StatusRow(Icons.Outlined.Autorenew, "Engine", "${live.engineRpm} rpm")
            }
            if (live.message.isNotBlank() && live.dongle == DongleState.ERROR) {
                ProblemBanner(live.message)
            }
            if (live.elmVersion.isNotBlank() && live.dongle == DongleState.CONNECTED) Hint(live.elmVersion)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(
                    if (live.serviceRunning) "Monitoring - holds the dongle while the car is nearby"
                    else "Wakes up automatically when you get in the car",
                    Modifier.weight(1f),
                )
                if (live.serviceRunning) {
                    TextButton(onClick = { TripService.send(ctx, TripService.ACTION_STOP) }) { Text("Release") }
                } else {
                    TextButton(enabled = s.dongleMac != null && perms,
                        onClick = { TripService.start(ctx, "manual connect") }) { Text("Connect") }
                }
            }
        }

        // ---------------------------------------------------------------- LubeLogger
        Section("LubeLogger", icon = Icons.Outlined.CloudUpload,
            subtitle = s.vehicleName.ifBlank { "No vehicle selected" }) {
            StatusRow(Icons.Outlined.CheckCircle, "Last upload",
                if (trip.lastUploadedValue >= 0) "${nf.format(trip.lastUploadedValue)} ${s.uploadUnit.label} · ${relativeTime(trip.lastUploadedAt, now)}"
                else "Not yet")
            StatusRow(Icons.Outlined.CloudUpload, "Waiting to upload",
                if (trip.pendingKm > 0) "${nf.format(s.toUploadValue(trip.pendingKm))} ${s.uploadUnit.label}" else "Nothing",
                if (trip.pendingKm > 0) Tone.BUSY else null)
            if (trip.lastUploadError.isNotBlank()) ProblemBanner(trip.lastUploadError)
            if (!s.lubeLoggerConfigured) {
                FilledTonalButton(onClick = onOpenSettings) { Text("Set up LubeLogger") }
            }
        }
    }
}

@Composable
private fun TripPill(phase: TripPhase, running: Boolean, startKm: Long, lastKm: Long, unit: UploadUnit) {
    val container = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    when {
        phase == TripPhase.ACTIVE -> {
            val km = if (startKm > 0 && lastKm > 0) lastKm - startKm else 0
            val d = if (unit == UploadUnit.MILES) (km * SettingsSnapshot.KM_TO_MI).roundToLong() else km
            StatusPill("Driving · +$d ${unit.label}", Tone.GOOD, container)
        }
        running -> StatusPill("Waiting for ignition", Tone.BUSY, container)
        else -> StatusPill("Parked", Tone.IDLE, container)
    }
}

@Composable
private fun ProblemBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Medium)
        }
    }
}
