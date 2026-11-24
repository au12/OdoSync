package cc.adamu.odosync.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.adamu.odosync.BuildConfig
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
fun SettingsScreen(activity: MainActivity) {
    val ctx = LocalContext.current
    val store = Settings.get(ctx)
    val trips = TripStore.get(ctx)
    val perms by activity.permissionsGranted

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
            .padding(PaddingValues(16.dp, 8.dp, 16.dp, 32.dp)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LubeLoggerSection(store, trips)
        DongleSection(activity, perms)
        TripDetectionSection(store, perms)
        OdometerSection(store, trips)
        AdvancedSection(activity, store, perms)
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Hint("OdoSync ${BuildConfig.VERSION_NAME}")
            Hint("github.com/au12/OdoSync", Modifier.clickable {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/au12/OdoSync".toUri()))
            }.padding(4.dp))
        }
    }
}

// ------------------------------------------------------------------- LubeLogger

@Composable
private fun LubeLoggerSection(store: Settings, trips: TripStore) {
    val s by store.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var vehicles by remember { mutableStateOf<List<LubeLoggerClient.Vehicle>>(emptyList()) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    var showSecret by remember { mutableStateOf(false) }

    fun test() {
        testState = TestState.Running
        scope.launch {
            testState = try {
                val list = withContext(Dispatchers.IO) { LubeLoggerClient(store.current).vehicles() }
                vehicles = list
                if (list.size == 1 && store.current.vehicleId == 0) selectVehicle(store, trips, list[0])
                TestState.Ok("Connected · ${list.size} vehicle${if (list.size == 1) "" else "s"}")
            } catch (e: Exception) {
                TestState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
    // Load the vehicle list automatically once credentials exist.
    LaunchedEffect(Unit) { if (store.current.baseUrl.isNotBlank() && vehicles.isEmpty()) test() }

    Section("LubeLogger", icon = Icons.Outlined.CloudUpload, subtitle = "Where odometer readings are sent") {
        OutlinedTextField(
            value = s.baseUrl, onValueChange = { v -> store.update { putString("baseUrl", v.trim()) } },
            label = { Text("Server URL") }, placeholder = { Text("https://lubelogger.example.com") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AuthMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = s.authMode == m,
                    onClick = { store.update { putString("authMode", m.name) } },
                    shape = SegmentedButtonDefaults.itemShape(i, AuthMode.entries.size),
                ) { Text(if (m == AuthMode.API_KEY) "API key" else "Login") }
            }
        }
        val eye: @Composable () -> Unit = {
            IconButton(onClick = { showSecret = !showSecret }) {
                Icon(if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (showSecret) "Hide" else "Show")
            }
        }
        val mask = if (showSecret) VisualTransformation.None else PasswordVisualTransformation()
        if (s.authMode == AuthMode.API_KEY) {
            OutlinedTextField(
                value = s.apiKey, onValueChange = { v -> store.update { putString("apiKey", v.trim()) } },
                label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = mask, trailingIcon = eye,
                supportingText = { Text("LubeLogger → Settings → API keys, Editor role") },
            )
        } else {
            OutlinedTextField(
                value = s.username, onValueChange = { v -> store.update { putString("username", v) } },
                label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.password, onValueChange = { v -> store.update { putString("password", v) } },
                label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = mask, trailingIcon = eye,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(enabled = testState != TestState.Running && s.baseUrl.isNotBlank(), onClick = { test() }) {
                Text(if (vehicles.isEmpty()) "Connect" else "Refresh")
            }
            Spacer(Modifier.width(12.dp))
            when (val t = testState) {
                TestState.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                is TestState.Ok -> ResultLabel(t.msg, ok = true)
                is TestState.Failed -> ResultLabel(t.msg, ok = false)
                TestState.Idle -> Unit
            }
        }
        SelectField(
            label = "Vehicle",
            value = s.vehicleName,
            placeholder = if (vehicles.isEmpty()) "Connect first" else "Choose…",
            options = vehicles,
            optionLabel = { it.label },
            enabled = vehicles.isNotEmpty(),
        ) { selectVehicle(store, trips, it) }
        if (s.vehicleId != 0) {
            var latest by remember(s.vehicleId) { mutableStateOf<String?>(null) }
            LaunchedEffect(s.vehicleId, s.baseUrl, s.apiKey) {
                latest = withContext(Dispatchers.IO) {
                    runCatching { LubeLoggerClient(store.current).latestOdometer(s.vehicleId)?.toString() }.getOrNull()
                }
            }
            Hint("LubeLogger currently shows ${latest ?: "…"} for this vehicle")
        }
    }
}

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val msg: String) : TestState
    data class Failed(val msg: String) : TestState
}

@Composable
private fun ResultLabel(text: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
            tint = if (ok) toneColor(Tone.GOOD) else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}

private fun selectVehicle(settings: Settings, trip: TripStore, v: LubeLoggerClient.Vehicle) {
    if (settings.current.vehicleId != v.id) trip.resetUploadBaseline()
    settings.update { putInt("vehicleId", v.id); putString("vehicleName", v.label) }
}

// ------------------------------------------------------------------- Dongle

@Composable
private fun DongleSection(activity: MainActivity, perms: Boolean) {
    val ctx = LocalContext.current
    val store = Settings.get(ctx)
    val s by store.flow.collectAsStateWithLifecycle()
    Section("OBD dongle", icon = Icons.Outlined.Sensors, subtitle = "Veepeak OBDCheck BLE in the driver's footwell") {
        if (s.dongleMac == null) {
            Hint("Plug the dongle in and switch the ignition on, then pair. Don't pair it in Android's " +
                "Bluetooth settings - OdoSync talks to it over Bluetooth LE directly.")
            Button(enabled = perms, onClick = { activity.pairing.start() }) { Text("Pair dongle") }
            if (!perms) Hint("Needs the Nearby devices permission first.")
        } else {
            val associated = remember(s.dongleMac) { DonglePairing.isAssociated(ctx, s.dongleMac) }
            StatusRow(Icons.Outlined.Bluetooth, s.dongleName ?: "OBD dongle", s.dongleMac!!,
                if (associated) Tone.GOOD else Tone.BAD)
            if (!associated) Hint("The system pairing is missing - unpair and pair again.")
            StatusRow(Icons.Outlined.Speed, "Odometer source",
                s.odoSource?.let(::sourceName) ?: "Detected on first read")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { DonglePairing.unpair(ctx) }) { Text("Unpair") }
                OutlinedButton(onClick = {
                    store.update { remove("odoSource"); remove("bmwVariableDlc") }
                    EventLog.log("Odometer source reset; will auto-detect on next read")
                }) { Text("Re-detect source") }
            }
        }
    }
}

// ------------------------------------------------------------------- Trip detection

@Composable
private fun TripDetectionSection(store: Settings, perms: Boolean) {
    val s by store.flow.collectAsStateWithLifecycle()
    Section("Trip detection", icon = Icons.Outlined.Route, subtitle = "How OdoSync knows you're driving") {
        Text("Car Bluetooth", style = MaterialTheme.typography.labelLarge)
        Hint("OdoSync only connects to the dongle after your phone joins one of these (your wireless " +
            "Android Auto adapter or head unit), and lets go when it disconnects. With none ticked, any " +
            "device that reports itself as car audio counts.")
        BondedDevicePicker(s.carBtMacs, perms) { macs -> store.update { putStringSet("carBtMacs", macs) } }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow("Android Auto", "Treat projecting to the car as a trip signal", s.useAndroidAuto) {
            store.update { putBoolean("useAndroidAuto", it) }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BondedDevicePicker(selected: Set<String>, permitted: Boolean, onChange: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    val devices = remember(permitted) {
        runCatching {
            ctx.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
                .map { (it.name ?: it.address) to it.address }
                .sortedWith(compareByDescending<Pair<String, String>> { it.second in selected }.thenBy { it.first.lowercase() })
        }.getOrDefault(emptyList())
    }
    if (devices.isEmpty()) {
        Hint(if (permitted) "No paired Bluetooth devices." else "Grant the Nearby devices permission to list devices.")
        return
    }
    Column {
        devices.forEach { (name, mac) ->
            val checked = mac in selected
            Row(
                Modifier.fillMaxWidth().clickable { onChange(if (checked) selected - mac else selected + mac) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { on -> onChange(if (on) selected + mac else selected - mac) })
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(mac, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ------------------------------------------------------------------- Odometer

@Composable
private fun OdometerSection(store: Settings, trips: TripStore) {
    val s by store.flow.collectAsStateWithLifecycle()
    Section("Odometer records", icon = Icons.Outlined.Speed, subtitle = "What gets written to LubeLogger") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            UploadUnit.entries.forEachIndexed { i, u ->
                SegmentedButton(
                    selected = s.uploadUnit == u,
                    onClick = { store.update { putString("uploadUnit", u.name) }; trips.resetUploadBaseline() },
                    shape = SegmentedButtonDefaults.itemShape(i, UploadUnit.entries.size),
                ) { Text(if (u == UploadUnit.MILES) "Miles" else "Kilometres") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IntField("Offset", s.offset, "± to match the dash", Modifier.weight(1f)) {
                store.update { putInt("offset", it) }; trips.resetUploadBaseline()
            }
            IntField("Min. change", s.minDelta, "Skip smaller trips", Modifier.weight(1f)) {
                store.update { putInt("minDelta", it.coerceAtLeast(0)) }
            }
        }
        OutlinedTextField(value = s.notes, onValueChange = { v -> store.update { putString("notes", v) } },
            label = { Text("Notes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = s.tag, onValueChange = { v -> store.update { putString("tag", v.trim()) } },
            label = { Text("Tag") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Lets you filter OdoSync's records in LubeLogger") })
        SwitchRow("Upload on Wi-Fi only", "Otherwise mobile data is used after a trip", s.wifiOnly) {
            store.update { putBoolean("wifiOnly", it) }
        }
    }
}

// ------------------------------------------------------------------- Advanced

@Composable
private fun AdvancedSection(activity: MainActivity, store: Settings, perms: Boolean) {
    val ctx = LocalContext.current
    val s by store.flow.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    Section("Advanced", icon = Icons.Outlined.Tune, trailing = {
        IconButton(onClick = { open = !open }) {
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Collapse" else "Expand")
        }
    }) {
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ignition voltage: %.1f V".format(s.voltageThreshold), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = s.voltageThreshold, valueRange = 12.8f..14.0f, steps = 11,
                    onValueChange = { v -> store.update { putFloat("voltageThreshold", (v * 10).toInt() / 10f) } },
                )
                Hint("Without Android Auto or car Bluetooth, a jump above this (alternator charging) triggers one check of the car.")
                OutlinedTextField(
                    value = s.dongleNamePattern,
                    onValueChange = { v -> store.update { putString("dongleNamePattern", v) } },
                    label = { Text("Dongle name filter (regex)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val ignoring = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
                StatusRow(Icons.Outlined.CheckCircle, "Background use", if (ignoring) "Unrestricted" else "Optimised",
                    if (ignoring) Tone.GOOD else Tone.BUSY)
                if (!ignoring) {
                    FilledTonalButton(onClick = {
                        @SuppressLint("BatteryLife")
                        val i = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            "package:${ctx.packageName}".toUri())
                        ctx.startActivity(i)
                    }) { Text("Allow background use") }
                }
                StatusRow(Icons.Outlined.Bluetooth, "Nearby devices", if (perms) "Granted" else "Not granted",
                    if (perms) Tone.GOOD else Tone.BAD)
                if (!perms) Button(onClick = { activity.requestPermissions() }) { Text("Grant permission") }
            }
        }
        if (!open) Hint("Ignition voltage, dongle name filter, background permissions")
    }
}

// ------------------------------------------------------------------- shared bits

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IntField(label: String, value: Int, help: String, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; t.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        supportingText = { Text(help) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
