package cc.adamu.odosync.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.adamu.odosync.data.AuthMode
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.data.TripStore
import cc.adamu.odosync.data.UploadUnit
import cc.adamu.odosync.upload.LubeLoggerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SetupScreen(activity: MainActivity) {
    val ctx = LocalContext.current
    val settingsStore = Settings.get(ctx)
    val tripStore = TripStore.get(ctx)
    val s by settingsStore.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var vehicles by remember { mutableStateOf<List<LubeLoggerClient.Vehicle>>(emptyList()) }
    var testMsg by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var vehicleMenu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ------------------------------------------------------------- LubeLogger
        SectionCard("1 · LubeLogger") {
            OutlinedTextField(
                value = s.baseUrl, onValueChange = { v -> settingsStore.update { putString("baseUrl", v.trim()) } },
                label = { Text("Server URL") }, placeholder = { Text("https://lubelogger.example.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AuthMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = s.authMode == m,
                        onClick = { settingsStore.update { putString("authMode", m.name) } },
                        shape = SegmentedButtonDefaults.itemShape(i, AuthMode.entries.size),
                    ) { Text(if (m == AuthMode.API_KEY) "API key" else "Username / password") }
                }
            }
            if (s.authMode == AuthMode.API_KEY) {
                OutlinedTextField(
                    value = s.apiKey, onValueChange = { v -> settingsStore.update { putString("apiKey", v.trim()) } },
                    label = { Text("API key (Editor)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Hint("Create one in LubeLogger → Settings → API keys with the Editor role.")
            } else {
                OutlinedTextField(
                    value = s.username, onValueChange = { v -> settingsStore.update { putString("username", v) } },
                    label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.password, onValueChange = { v -> settingsStore.update { putString("password", v) } },
                    label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !testing && s.baseUrl.isNotBlank(), onClick = {
                    testing = true
                    testMsg = "Connecting…"
                    scope.launch {
                        testMsg = try {
                            val list = withContext(Dispatchers.IO) { LubeLoggerClient(settingsStore.current).vehicles() }
                            vehicles = list
                            if (list.size == 1 && s.vehicleId == 0) selectVehicle(settingsStore, tripStore, list[0])
                            "OK - ${list.size} vehicle(s)"
                        } catch (e: Exception) {
                            "Failed: ${e.message ?: e.javaClass.simpleName}"
                        }
                        testing = false
                    }
                }) { Text("Test & load vehicles") }
                if (testMsg.isNotBlank()) Text(testMsg, style = MaterialTheme.typography.bodySmall)
            }
            Box {
                OutlinedButton(enabled = vehicles.isNotEmpty(), onClick = { vehicleMenu = true }) {
                    Text(if (s.vehicleId != 0) "Vehicle: ${s.vehicleName}" else "Choose vehicle")
                }
                DropdownMenu(expanded = vehicleMenu, onDismissRequest = { vehicleMenu = false }) {
                    vehicles.forEach { v ->
                        DropdownMenuItem(text = { Text(v.label) }, onClick = {
                            selectVehicle(settingsStore, tripStore, v)
                            vehicleMenu = false
                        })
                    }
                }
            }
            if (s.vehicleId != 0) {
                var latest by remember(s.vehicleId) { mutableStateOf<String?>(null) }
                LaunchedEffect(s.vehicleId, s.baseUrl, s.apiKey) {
                    latest = withContext(Dispatchers.IO) {
                        runCatching { LubeLoggerClient(settingsStore.current).latestOdometer(s.vehicleId)?.toString() }
                            .getOrNull()
                    }
                }
                Hint("LubeLogger's current odometer: ${latest ?: "…"}")
            }
        }

        // ------------------------------------------------------------- Dongle
        SectionCard("2 · OBD dongle") {
            if (s.dongleMac == null) {
                Hint("Plug the Veepeak into the OBD port (driver footwell), switch the ignition on, then pair. " +
                    "Don't pair it in Android's Bluetooth settings - OdoSync uses Bluetooth LE directly.")
                Button(enabled = activity.permissionsGranted.value, onClick = { activity.pairing.start() }) {
                    Text("Pair dongle")
                }
            } else {
                KeyValue("Paired", "${s.dongleName ?: "OBD"} · ${s.dongleMac}")
                val associated = remember(s.dongleMac) { DonglePairing.isAssociated(ctx, s.dongleMac) }
                if (!associated) Hint("⚠ Companion association missing - unpair and pair again.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { DonglePairing.unpair(ctx) }) { Text("Unpair") }
                    OutlinedButton(onClick = {
                        settingsStore.update { remove("odoSource"); remove("bmwVariableDlc") }
                        EventLog.log("Odometer source reset; will auto-detect on next read")
                    }) { Text("Re-detect source") }
                }
                s.odoSource?.let { Hint("Odometer source: $it") }
            }
            OutlinedTextField(
                value = s.dongleNamePattern,
                onValueChange = { v -> settingsStore.update { putString("dongleNamePattern", v) } },
                label = { Text("Name filter (regex, used when pairing)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ------------------------------------------------------------- Trip detection
        SectionCard("3 · Trip detection") {
            Hint("The car's Bluetooth connecting (your wireless Android Auto link) is the most reliable " +
                "\"I'm driving\" signal - pick it here.")
            BondedDevicePicker(s.carBtMacs, activity.permissionsGranted.value) { macs ->
                settingsStore.update { putStringSet("carBtMacs", macs) }
            }
            ToggleRow("Use Android Auto projection as a trip signal", s.useAndroidAuto) {
                settingsStore.update { putBoolean("useAndroidAuto", it) }
            }
            Text("Ignition voltage threshold: %.1f V".format(s.voltageThreshold), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = s.voltageThreshold, valueRange = 12.8f..14.0f, steps = 11,
                onValueChange = { v -> settingsStore.update { putFloat("voltageThreshold", (v * 10).toInt() / 10f) } },
            )
            Hint("Without Android Auto/Bluetooth, a jump above this voltage (alternator charging) triggers one probe of the car.")
        }

        // ------------------------------------------------------------- Odometer
        SectionCard("4 · Odometer upload") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                UploadUnit.entries.forEachIndexed { i, u ->
                    SegmentedButton(
                        selected = s.uploadUnit == u,
                        onClick = {
                            settingsStore.update { putString("uploadUnit", u.name) }
                            tripStore.resetUploadBaseline()
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, UploadUnit.entries.size),
                    ) { Text(if (u == UploadUnit.MILES) "Miles" else "Kilometres") }
                }
            }
            Hint("The car reports whole kilometres; miles are converted (±1 mi vs. the dash).")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField("Offset", s.offset, Modifier.weight(1f)) {
                    settingsStore.update { putInt("offset", it) }; tripStore.resetUploadBaseline()
                }
                IntField("Min. change", s.minDelta, Modifier.weight(1f)) {
                    settingsStore.update { putInt("minDelta", it.coerceAtLeast(0)) }
                }
            }
            OutlinedTextField(value = s.tag, onValueChange = { v -> settingsStore.update { putString("tag", v.trim()) } },
                label = { Text("Tag") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = s.notes, onValueChange = { v -> settingsStore.update { putString("notes", v) } },
                label = { Text("Notes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ToggleRow("Upload only on Wi-Fi", s.wifiOnly) { settingsStore.update { putBoolean("wifiOnly", it) } }
        }

        // ------------------------------------------------------------- Background
        SectionCard("5 · Background") {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            KeyValue("Battery optimisation", if (ignoring) "Unrestricted" else "Optimised")
            if (!ignoring) {
                Hint("Pairing the dongle normally exempts OdoSync automatically. If not, allow it here.")
                FilledTonalButton(onClick = {
                    @SuppressLint("BatteryLife")
                    val i = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}"))
                    ctx.startActivity(i)
                }) { Text("Allow background use") }
            }
            if (!activity.permissionsGranted.value) {
                Button(onClick = { activity.requestPermissions() }) { Text("Grant Bluetooth permission") }
            }
        }
    }
}

private fun selectVehicle(settings: Settings, trip: TripStore, v: LubeLoggerClient.Vehicle) {
    if (settings.current.vehicleId != v.id) trip.resetUploadBaseline()
    settings.update { putInt("vehicleId", v.id); putString("vehicleName", v.label) }
}

@SuppressLint("MissingPermission")
@Composable
private fun BondedDevicePicker(selected: Set<String>, permitted: Boolean, onChange: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    val devices = remember(permitted) {
        runCatching {
            ctx.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
                .map { (it.name ?: it.address) to it.address }
                .sortedBy { it.first.lowercase() }
        }.getOrDefault(emptyList())
    }
    if (devices.isEmpty()) {
        Hint("No paired Bluetooth devices visible (grant the Bluetooth permission).")
        return
    }
    devices.forEach { (name, mac) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = mac in selected, onCheckedChange = { on ->
                onChange(if (on) selected + mac else selected - mac)
            })
            Column {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                Text(mac, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IntField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
