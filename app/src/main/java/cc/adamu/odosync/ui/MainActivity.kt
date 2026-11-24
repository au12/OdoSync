package cc.adamu.odosync.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.adamu.odosync.data.EventLog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import cc.adamu.odosync.data.LiveStatus
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.service.CarBtState
import cc.adamu.odosync.service.TripService

class MainActivity : ComponentActivity() {

    lateinit var pairing: DonglePairing
        private set

    val permissionsGranted = mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissions()
            if (permissionsGranted.value) maybeStartService("app opened")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pairing = DonglePairing(this)
        refreshPermissions()
        setContent { OdoSyncTheme { Root(this) } }
        if (!permissionsGranted.value) requestPermissions() else maybeStartService("app opened")
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun requestPermissions() = permissionLauncher.launch(requiredPermissions())

    private fun refreshPermissions() {
        permissionsGranted.value = requiredPermissions()
            .filter { it != Manifest.permission.POST_NOTIFICATIONS }
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Opening the app only joins in if you're already in the car; it never connects at home. */
    fun maybeStartService(reason: String) {
        if (Settings.get(this).current.dongleMac != null && !LiveStatus.current.serviceRunning &&
            CarBtState.anyConnected()) {
            TripService.start(this, reason)
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Outlined.Speed),
    ACTIVITY("Activity", Icons.Outlined.History),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(activity: MainActivity) {
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    val ctx = LocalContext.current
    val settings by Settings.get(ctx).flow.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    when (tab) {
                        Tab.DASHBOARD -> Column {
                            Text("OdoSync", style = MaterialTheme.typography.titleLarge)
                            if (settings.vehicleName.isNotBlank()) {
                                Text(settings.vehicleName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        else -> Text(tab.label)
                    }
                },
                actions = {
                    if (tab == Tab.ACTIVITY) {
                        IconButton(onClick = { shareLog(ctx) }) { Icon(Icons.Outlined.Share, "Share log") }
                        IconButton(onClick = { EventLog.clear() }) { Icon(Icons.Outlined.DeleteSweep, "Clear log") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                Tab.DASHBOARD -> DashboardScreen(activity, onOpenSettings = { tab = Tab.SETTINGS })
                Tab.ACTIVITY -> ActivityScreen()
                Tab.SETTINGS -> SettingsScreen(activity)
            }
        }
    }
}
