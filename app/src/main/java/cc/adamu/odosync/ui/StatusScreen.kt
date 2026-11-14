package cc.adamu.odosync.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.adamu.odosync.data.DongleState
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.data.LiveStatus
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.data.TripPhase
import cc.adamu.odosync.data.TripStore
import cc.adamu.odosync.service.TripService
import cc.adamu.odosync.upload.UploadWorker
import java.text.NumberFormat

@Composable
fun StatusScreen(activity: MainActivity, onOpenSetup: () -> Unit) {
    val ctx = LocalContext.current
    val settings by Settings.get(ctx).flow.collectAsStateWithLifecycle()
    val trip by TripStore.get(ctx).flow.collectAsStateWithLifecycle()
    val live by LiveStatus.flow.collectAsStateWithLifecycle()
    val log by EventLog.flow.collectAsStateWithLifecycle()
    val perms by activity.permissionsGranted
    val nf = NumberFormat.getIntegerInstance()
    val good = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!perms) {
            SectionCard("Permission needed") {
                Hint("OdoSync needs “Nearby devices” to talk to the OBD dongle over Bluetooth LE.")
                Button(onClick = { activity.requestPermissions() }) { Text("Grant") }
            }
        }
        if (settings.dongleMac == null || !settings.lubeLoggerConfigured) {
            SectionCard("Finish setup") {
                if (settings.dongleMac == null) Hint("• Pair the Veepeak dongle")
                if (!settings.lubeLoggerConfigured) Hint("• Connect LubeLogger and pick the vehicle")
                if (settings.carBtMacs.isEmpty()) Hint("• Recommended: choose the car's Bluetooth device")
                FilledTonalButton(onClick = onOpenSetup) { Text("Open setup") }
            }
        }

        // Odometer
        SectionCard(null) {
            Text("Odometer", style = MaterialTheme.typography.labelLarge, color = muted)
            val value = if (trip.lastKm > 0) nf.format(settings.toUploadValue(trip.lastKm)) else "—"
            Text("$value ${settings.uploadUnit.label}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            if (trip.lastKm > 0) {
                Hint("${nf.format(trip.lastKm)} km from ${trip.lastSource} · read ${formatTime(trip.lastKmAt)}")
            } else {
                Hint("No reading yet - it appears on the first drive (or tap Read now with the ignition on).")
            }
            val phaseText = when (live.phase) {
                TripPhase.ACTIVE -> {
                    val d = if (live.tripStartKm > 0 && trip.lastKm > 0) trip.lastKm - live.tripStartKm else 0
                    "Trip since ${formatTime(live.tripSince)} · +$d km"
                }
                else -> if (live.serviceRunning) live.phase.label else "Sleeping until the car shows up"
            }
            AssistChip(onClick = {}, label = { Text(phaseText) })
        }

        // Car link
        SectionCard("Car link") {
            val dongleText = when (live.dongle) {
                DongleState.CONNECTED -> "Connected" + (live.elmVersion.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                DongleState.NOT_PAIRED -> if (settings.dongleMac == null) "Not paired" else "Service idle"
                else -> live.dongle.label
            }
            KeyValue("Dongle", dongleText, if (live.dongle == DongleState.CONNECTED) good else muted)
            KeyValue("Android Auto", if (!settings.useAndroidAuto) "Ignored" else if (live.androidAuto) "Projecting" else "Not connected",
                if (live.androidAuto) good else muted)
            KeyValue("Car Bluetooth", when {
                settings.carBtMacs.isEmpty() -> "Not set"
                live.carBluetooth -> "Connected"
                else -> "Not connected"
            }, if (live.carBluetooth) good else muted)
            KeyValue("Battery (OBD pin 16)", live.voltage?.let { "%.1f V".format(it) } ?: "—")
            if (live.phase == TripPhase.ACTIVE) KeyValue("Engine", live.engineRpm?.let { "$it rpm" } ?: "—")
            if (live.message.isNotBlank()) Hint(live.message)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = settings.dongleMac != null, onClick = {
                    TripService.send(ctx, TripService.ACTION_READ_NOW)
                }) { Text("Read now") }
                if (live.serviceRunning) {
                    OutlinedButton(onClick = { TripService.send(ctx, TripService.ACTION_STOP) }) { Text("Release dongle") }
                } else {
                    OutlinedButton(enabled = settings.dongleMac != null,
                        onClick = { TripService.start(ctx, "manual connect") }) { Text("Connect") }
                }
            }
        }

        // LubeLogger
        SectionCard("LubeLogger") {
            KeyValue("Vehicle", settings.vehicleName.ifBlank { "—" })
            KeyValue("Last upload",
                if (trip.lastUploadedValue >= 0) "${nf.format(trip.lastUploadedValue)} ${settings.uploadUnit.label} · ${formatTime(trip.lastUploadedAt)}" else "—")
            KeyValue("Pending",
                if (trip.pendingKm > 0) "${nf.format(settings.toUploadValue(trip.pendingKm))} ${settings.uploadUnit.label}" else "Nothing")
            if (trip.lastUploadError.isNotBlank()) {
                Text(trip.lastUploadError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(enabled = trip.pendingKm > 0 && settings.lubeLoggerConfigured,
                onClick = { UploadWorker.enqueue(ctx, "manual") }) { Text("Upload now") }
        }

        // Log
        SectionCard("Activity log") {
            val shown = log.takeLast(80).reversed()
            if (shown.isEmpty()) Hint("Nothing yet.")
            shown.forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { EventLog.clear() }) { Text("Clear") }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "OdoSync log")
                        .putExtra(Intent.EXTRA_TEXT, EventLog.dump())
                    ctx.startActivity(Intent.createChooser(send, "Share log"))
                }) { Text("Share") }
            }
        }
    }
}
