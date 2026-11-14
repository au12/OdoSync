package cc.adamu.odosync.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import cc.adamu.odosync.data.LiveStatus
import cc.adamu.odosync.data.Settings
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

    /** Opening the app connects to the dongle if it's paired, so you can watch it work. */
    fun maybeStartService(reason: String) {
        if (Settings.get(this).current.dongleMac != null && !LiveStatus.current.serviceRunning) {
            TripService.start(this, reason)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(activity: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (tab == 0) "OdoSync" else "Setup") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("◉") }, label = { Text("Status") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Text("⚙") }, label = { Text("Setup") })
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (tab == 0) StatusScreen(activity, onOpenSetup = { tab = 1 }) else SetupScreen(activity)
        }
    }
}

@Composable
fun OdoSyncTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}
