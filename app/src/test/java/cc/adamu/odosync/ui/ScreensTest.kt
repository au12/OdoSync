package cc.adamu.odosync.ui

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import cc.adamu.odosync.data.DongleState
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.data.LiveStatus
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.data.Status
import cc.adamu.odosync.data.TripPhase
import cc.adamu.odosync.data.TripStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Renders every screen with realistic data. Fails if a screen crashes while composing; with
 * -PrecordScreenshots it also refreshes the PNGs in docs/screenshots used by the README.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@OptIn(ExperimentalTestApi::class)
class ScreensTest {

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var server: ServerSocket
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun seed() {
        // A fake LubeLogger so Settings shows a successful connection.
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { c ->
                    val path = c.getInputStream().bufferedReader().readLine().orEmpty().split(" ").getOrElse(1) { "/" }
                    val body = when {
                        path.startsWith("/api/vehicles") ->
                            """[{"id":1,"year":2016,"make":"BMW","model":"435i xDrive Gran Coupe","licensePlate":""},""" +
                                """{"id":2,"year":2018,"make":"Volkswagen","model":"Tiguan","licensePlate":""}]"""
                        path.startsWith("/api/vehicle/odometerrecords/latest") -> "61207"
                        else -> "[]"
                    }.toByteArray()
                    c.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }

        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        val car = adapter.getRemoteDevice("00:1A:7D:DA:71:13").also { shadowOf(it).setName("BMW 41733") }
        val aa = adapter.getRemoteDevice("54:2B:57:0C:44:10").also { shadowOf(it).setName("MMI-BOX AA") }
        val buds = adapter.getRemoteDevice("F4:7B:09:31:9C:02").also { shadowOf(it).setName("Pixel Buds Pro") }
        shadowOf(adapter).setBondedDevices(setOf(car, aa, buds))

        app.getSystemService(CompanionDeviceManager::class.java)
            .let { shadowOf(it).addAssociation("8C:DE:52:A1:07:3F") }
        Settings.get(app).update {
            putString("baseUrl", "http://127.0.0.1:${server.localPort}")
            putString("apiKey", "3f757919f42548cebec90a692815e048")
            putInt("vehicleId", 1)
            putString("vehicleName", "2016 BMW 435i xDrive Gran Coupe")
            putString("dongleMac", "8C:DE:52:A1:07:3F")
            putString("dongleName", "VEEPEAK")
            putString("odoSource", "KOMBI")
            putStringSet("carBtMacs", setOf("54:2B:57:0C:44:10"))
        }
        val trips = TripStore.get(app)
        trips.recordReading(98_502, "KOMBI")
        trips.markUploaded(98_502, Settings.get(app).current.toUploadValue(98_502))
        trips.recordReading(98_550, "KOMBI")

        val now = System.currentTimeMillis()
        LiveStatus.update {
            Status(serviceRunning = true, dongle = DongleState.CONNECTED, elmVersion = "ELM327 v2.2",
                androidAuto = true, carBluetooth = true, voltage = 14.2f, phase = TripPhase.ACTIVE,
                tripSince = now - 23 * 60_000, tripStartKm = 98_531, engineRpm = 1850, lastPollAt = now)
        }

        EventLog.init(app)
        EventLog.clear()
        listOf(
            "Car Bluetooth connected (54:2B:57:0C:44:10)",
            "Service woken: car Bluetooth",
            "BLE: serial on FFF0 notify=FFF1 write=FFF2",
            "ELM: ready (ELM327 v2.2)",
            "Android Auto connected",
            "Trip started (Android Auto) at 98531 km = 61225 mi",
            "Trip ended (car Bluetooth/Android Auto disconnected): 98502 km = 61207 mi, +18 km",
            "Upload queued (trip end)",
            "Uploaded odometer 61207 mi to LubeLogger (Odometer Record Added)",
            "Upload failed: HTTP 401 (check the API key / credentials and that the key has Editor rights)",
        ).forEach(EventLog::log)
    }

    @After
    fun stop() {
        server.close()
    }

    @Test fun dashboardLight() = shoot("dashboard-light")

    @Test @Config(qualifiers = "+night") fun dashboardDark() = shoot("dashboard-dark")

    @Test fun activity() = shoot("activity", tab = "Activity")

    @Test fun settings() = shoot("settings", tab = "Settings", waitFor = "Connected · 2 vehicles")

    @Test @Config(qualifiers = "+night")
    fun settingsDark() = shoot("settings-dark", tab = "Settings", waitFor = "Connected · 2 vehicles")

    private fun shoot(name: String, tab: String? = null, waitFor: String? = null) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            if (tab != null) compose.onAllNodesWithText(tab)[0].performClick()
            if (waitFor != null) compose.waitUntil(10_000) {
                compose.onAllNodes(hasText(waitFor)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            compose.onRoot().assertExists()
            // Software-draw the window (PixelCopy-based captureToImage isn't available under Robolectric).
            lateinit var bitmap: Bitmap
            scenario.onActivity { act ->
                val v = act.window.decorView
                bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bitmap))
            }
            if (System.getProperty("screenshots.record") == "true") {
                val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
                File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
