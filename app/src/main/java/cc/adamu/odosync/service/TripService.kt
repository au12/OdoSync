package cc.adamu.odosync.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cc.adamu.odosync.OdoSyncApp
import cc.adamu.odosync.R
import cc.adamu.odosync.ble.BleSerial
import cc.adamu.odosync.data.DongleState
import cc.adamu.odosync.data.EventLog
import cc.adamu.odosync.data.LiveStatus
import cc.adamu.odosync.data.Settings
import cc.adamu.odosync.data.Status
import cc.adamu.odosync.data.TripPhase
import cc.adamu.odosync.data.TripStore
import cc.adamu.odosync.data.TripSummary
import cc.adamu.odosync.data.UploadOutcome
import cc.adamu.odosync.obd.CarReader
import cc.adamu.odosync.obd.Elm327
import cc.adamu.odosync.obd.OdoResult
import cc.adamu.odosync.obd.RpmResult
import cc.adamu.odosync.ui.MainActivity
import cc.adamu.odosync.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat

/**
 * Runs while the car is around. Life cycle of a drive:
 *
 *  1. Woken by: the car's Bluetooth connecting (ACL), the paired dongle appearing (companion
 *     device presence), or opening the app.
 *  2. Connects to the dongle over BLE and idles, reading only the adapter's own voltage
 *     (ATRV - no CAN traffic, so a parked car is never kept awake).
 *  3. A trip starts when Android Auto is projecting, the car's Bluetooth is linked, or the
 *     voltage jumps above the charging threshold - and the instrument cluster answers.
 *  4. During the trip the odometer is read once a minute (one tiny request).
 *  5. The trip ends when Android Auto/car Bluetooth drop and the engine is off, or the car stops
 *     answering. The last reading is queued for upload to LubeLogger (WorkManager, needs network).
 */
class TripService : LifecycleService() {

    companion object {
        const val ACTION_START = "cc.adamu.odosync.START"
        const val ACTION_DONGLE_GONE = "cc.adamu.odosync.DONGLE_GONE"
        const val ACTION_CAR_BT = "cc.adamu.odosync.CAR_BT"
        const val ACTION_READ_NOW = "cc.adamu.odosync.READ_NOW"
        const val ACTION_STOP = "cc.adamu.odosync.STOP"
        const val EXTRA_REASON = "reason"
        const val EXTRA_CONNECTED = "connected"

        private const val NOTIF_ID = 42
        private const val TICK_MS = 15_000L             // ATRV cadence (adapter-only, no CAN)
        private const val POLL_MS = 60_000L             // odometer cadence during a trip
        private const val PROBE_EVERY_MS = 60_000L      // retry cadence while a level trigger holds
        private const val ENGINE_OFF_GRACE_MS = 150_000L // > typical auto start/stop pause
        // Release the dongle and stop once the car's Bluetooth has been gone this long with no trip
        // (long enough to ride out a Bluetooth hiccup or finish a manual "Read now").
        private const val LINGER_MS = 90_000L

        fun start(context: Context, reason: String) =
            send(context, ACTION_START) { putExtra(EXTRA_REASON, reason) }

        fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, TripService::class.java).setAction(action).apply(extras)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                EventLog.log("Could not start trip service ($action): ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private val settings by lazy { Settings.get(this) }
    private val store by lazy { TripStore.get(this) }

    @Volatile private var aaConnected = false
    @Volatile private var carBtConnected = false
    @Volatile private var readNowRequested = false
    @Volatile private var stopping = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var serial: BleSerial? = null

    // Trip bookkeeping (only touched from the loop coroutine)
    private var tripActive = false
    private var tripStartKm = -1L
    private var suspectKm = -1L

    override fun onCreate() {
        super.onCreate()
        if (!goForeground("Starting…")) return
        LiveStatus.update { it.copy(serviceRunning = true, message = "") }

        CarConnection(this).type.observe(this) { type ->
            val aa = type == CarConnection.CONNECTION_TYPE_PROJECTION
            if (aa != aaConnected) {
                aaConnected = aa
                LiveStatus.update { it.copy(androidAuto = aa) }
                EventLog.log("Android Auto ${if (aa) "connected" else "disconnected"}")
                wake.trySend(Unit)
            }
        }

        loop = lifecycleScope.launch(Dispatchers.Default) {
            carBtConnected = CarBtState.anyConnected() ||
                CarBtState.probe(this@TripService, settings.current.carBtMacs)
            LiveStatus.update { it.copy(carBluetooth = carBtConnected) }
            try {
                runLoop()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    EventLog.log("Service error: ${e.javaClass.simpleName}: ${e.message}")
                }
            } finally {
                if (tripActive) endTrip("service stopping")
                serial?.close()
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground(notificationText())
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                stopping = false
                intent?.getStringExtra(EXTRA_REASON)?.let { EventLog.log("Connecting to dongle: $it") }
            }
            ACTION_DONGLE_GONE -> EventLog.log("Dongle no longer advertising")
            ACTION_CAR_BT -> {
                val c = intent?.getBooleanExtra(EXTRA_CONNECTED, false) ?: false
                carBtConnected = c || CarBtState.anyConnected()
                LiveStatus.update { it.copy(carBluetooth = carBtConnected) }
                if (c) stopping = false
            }
            ACTION_READ_NOW -> readNowRequested = true
            ACTION_STOP -> {
                EventLog.log("Stop requested")
                stopping = true
            }
        }
        wake.trySend(Unit)
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        loop?.cancel()
        serial?.close()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        LiveStatus.update {
            Status(serviceRunning = false, androidAuto = it.androidAuto, carBluetooth = it.carBluetooth,
                dongle = if (settings.current.dongleMac == null) DongleState.NOT_PAIRED else DongleState.STOPPED)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- main loop

    private suspend fun runLoop() {
        var firstAttempt = true
        var lastActivityAt = System.currentTimeMillis()
        while (lifecycleScope.isActive && !stopping) {
            val s = settings.current
            val mac = s.dongleMac
            if (mac == null) {
                setDongle(DongleState.NOT_PAIRED, "Pair the OBD dongle in the app")
                return
            }
            if (!hasBtPermission()) {
                setDongle(DongleState.ERROR, "Nearby devices permission missing")
                return
            }
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                setDongle(DongleState.ERROR, "Bluetooth is off")
                if (!carSignal() && idleFor(lastActivityAt)) return
                withTimeoutOrNull(30_000) { wake.receive() }
                continue
            }

            val link = BleSerial(this, adapter.getRemoteDevice(mac))
            serial = link
            val connected = if (firstAttempt) {
                setDongle(DongleState.CONNECTING)
                link.connect(autoConnect = false, timeoutMs = 20_000)
            } else {
                // Background reconnect: the controller watches for the dongle at low duty cycle.
                setDongle(DongleState.WAITING)
                link.connect(autoConnect = true, timeoutMs = 60_000L)
            }
            firstAttempt = false
            if (!connected) {
                if (carSignal()) lastActivityAt = System.currentTimeMillis()
                if (idleFor(lastActivityAt)) {
                    EventLog.log("Dongle not reachable and the car's Bluetooth is gone; stopping")
                    return
                }
                continue
            }

            setDongle(DongleState.INITIALISING)
            val elm = Elm327(link)
            elm.bmwVariableDlc = settings.current.bmwVariableDlc
            if (!elm.init()) {
                link.close()
                setDongle(DongleState.ERROR, "ELM327 did not respond")
                delay(10_000)
                continue
            }
            LiveStatus.update { it.copy(dongle = DongleState.CONNECTED, elmVersion = elm.version, message = "") }
            updateNotification()

            val reader = CarReader(elm).apply {
                onFrameVariantLearned = { v -> settings.update { putBoolean("bmwVariableDlc", v) } }
            }
            val idleExit = session(link, reader)
            link.close()
            if (tripActive) endTrip("dongle link lost")
            lastActivityAt = System.currentTimeMillis()
            if (idleExit) return
        }
    }

    /** Returns true when leaving because the car is gone (no Bluetooth/AA, no trip) for [LINGER_MS]. */
    private suspend fun session(link: BleSerial, reader: CarReader): Boolean {
        var probeArmed = true
        var lastProbeAt = 0L
        var lastOdoAt = 0L
        var failures = 0
        var lastEngineRunningAt = 0L
        var prevSignal = carSignal()
        var lastActivityAt = System.currentTimeMillis()

        while (link.connected.value && !stopping) {
            val now = System.currentTimeMillis()
            val s = settings.current

            // 1. Adapter voltage: free (no CAN frames), used as an "engine just started" hint.
            val v = reader.voltage()
            if (!link.connected.value) break
            LiveStatus.update { it.copy(voltage = v ?: it.voltage) }
            var voltageEdge = false
            if (v != null) {
                if (v >= s.voltageThreshold && probeArmed) { voltageEdge = true; probeArmed = false }
                if (v < s.voltageThreshold - 0.3f) probeArmed = true
            }

            val signal = carSignal()
            val signalFell = prevSignal && !signal
            prevSignal = signal
            val manual = readNowRequested.also { readNowRequested = false }

            if (!tripActive) {
                LiveStatus.update { it.copy(phase = TripPhase.WATCHING) }
                val trigger = when {
                    manual -> "manual read"
                    signal && now - lastProbeAt >= PROBE_EVERY_MS ->
                        if (aaConnected && s.useAndroidAuto) "Android Auto" else "car Bluetooth"
                    voltageEdge -> "voltage %.1f V".format(v)
                    else -> null
                }
                if (trigger != null) {
                    lastProbeAt = now
                    when (val r = reader.readOdometer(s.odoSource)) {
                        is OdoResult.Ok -> {
                            accept(r)
                            startTrip(r.km, trigger)
                            failures = 0
                            lastOdoAt = now
                            lastEngineRunningAt = now
                        }
                        OdoResult.NoResponse -> EventLog.log("Probe ($trigger): car not answering (asleep)")
                        is OdoResult.Failed -> EventLog.log("Probe ($trigger) failed: ${r.why}")
                    }
                    LiveStatus.update { it.copy(lastPollAt = now) }
                }
            } else {
                if (now - lastOdoAt >= POLL_MS || manual || signalFell) {
                    lastOdoAt = now
                    when (val r = reader.readOdometer(s.odoSource)) {
                        is OdoResult.Ok -> { accept(r); failures = 0 }
                        OdoResult.NoResponse -> failures++
                        is OdoResult.Failed -> failures++
                    }
                    // Engine state only matters when there's no AA/Bluetooth signal to go by
                    // (or it just dropped). With AA up we stay in BMW mode: one request a minute.
                    if (!signal || signalFell) {
                        when (val rpm = reader.readRpm()) {
                            is RpmResult.Ok -> {
                                LiveStatus.update { it.copy(engineRpm = rpm.rpm) }
                                if (rpm.rpm > 0) lastEngineRunningAt = now
                            }
                            else -> LiveStatus.update { it.copy(engineRpm = null) }
                        }
                    } else {
                        lastEngineRunningAt = now // AA/Bluetooth up = car in use
                    }
                    LiveStatus.update { it.copy(lastPollAt = now) }
                }
                val engineOffFor = now - lastEngineRunningAt
                // signalFell forced a fresh RPM read above, so this is current.
                val engineRunningNow = (LiveStatus.current.engineRpm ?: 0) > 0
                val endReason = when {
                    failures >= 3 -> "car stopped answering"
                    // AA/Bluetooth hiccup mid-drive with the engine running: keep the trip going.
                    signalFell && !engineRunningNow -> "car Bluetooth/Android Auto disconnected"
                    !signal && engineOffFor >= ENGINE_OFF_GRACE_MS -> "engine off"
                    else -> null
                }
                if (endReason != null) {
                    endTrip(endReason)
                    failures = 0
                    probeArmed = false // need the voltage to dip and rise again before re-probing
                    lastProbeAt = now
                }
            }

            if (tripActive || signal || manual) lastActivityAt = now
            if (!tripActive && now - lastActivityAt > LINGER_MS) {
                EventLog.log("Not in the car any more; releasing the dongle")
                return true
            }
            updateNotification()
            withTimeoutOrNull(TICK_MS) { wake.receive() }
        }
        return false
    }

    // ---------------------------------------------------------------- trip helpers

    private fun accept(r: OdoResult.Ok) {
        val last = store.current.lastKm
        if (last > 0 && (r.km < last || r.km > last + 3000) && r.km != suspectKm) {
            // Implausible jump: only believe it if the next read says the same thing.
            EventLog.log("Ignoring suspicious reading ${r.km} km (${r.source}); last was $last km")
            suspectKm = r.km
            return
        }
        suspectKm = -1
        store.recordReading(r.km, r.source)
        if (settings.current.odoSource != r.source) {
            settings.update { putString("odoSource", r.source) }
            EventLog.log("Odometer source: ${r.source}")
        }
    }

    private fun startTrip(km: Long, trigger: String) {
        tripActive = true
        tripStartKm = km
        store.markTrip(km, null)
        val s = settings.current
        EventLog.log("Trip started ($trigger) at $km km = ${s.toUploadValue(km)} ${s.uploadUnit.label}")
        LiveStatus.update { it.copy(phase = TripPhase.ACTIVE, tripSince = System.currentTimeMillis(), tripStartKm = km) }
    }

    private fun endTrip(reason: String) {
        tripActive = false
        val st = store.current
        val s = settings.current
        store.markTrip(null, st.lastKm)
        val driven = if (tripStartKm > 0 && st.lastKm > 0) st.lastKm - tripStartKm else 0
        EventLog.log("Trip ended ($reason): ${st.lastKm} km = ${s.toUploadValue(st.lastKm)} ${s.uploadUnit.label}, +$driven km")
        LiveStatus.update { it.copy(phase = TripPhase.IDLE, tripSince = 0, tripStartKm = -1, engineRpm = null) }
        var outcome = UploadOutcome.ALREADY_THERE
        if (st.pendingKm > 0) {
            val value = s.toUploadValue(st.pendingKm)
            outcome = when {
                !s.lubeLoggerConfigured -> UploadOutcome.NOT_CONFIGURED
                st.lastUploadedValue >= 0 && value < st.lastUploadedValue + s.minDelta -> UploadOutcome.BELOW_MIN
                s.wifiOnly -> UploadOutcome.WAITING_WIFI
                else -> UploadOutcome.QUEUED
            }
            if (outcome == UploadOutcome.BELOW_MIN) store.clearPendingIfAtMost(st.pendingKm)
            else if (outcome != UploadOutcome.NOT_CONFIGURED) UploadWorker.enqueue(this, "trip end")
        }
        // A dismissable summary replaces the ongoing notification once the car is gone.
        // Skip it for "trips" that went nowhere (sitting in the car with the radio on).
        if (driven > 0 && tripStartKm > 0) {
            store.saveSummary(TripSummary(tripStartKm, st.lastKm, store.current.tripStartAt.takeIf { it > 0 }
                ?: System.currentTimeMillis(), System.currentTimeMillis(), outcome))
            TripNotifier.show(this)
        }
        tripStartKm = -1
    }

    private fun carSignal(): Boolean =
        (settings.current.useAndroidAuto && aaConnected) || carBtConnected

    private fun idleFor(since: Long) = System.currentTimeMillis() - since > LINGER_MS

    private fun hasBtPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun setDongle(state: DongleState, message: String = "") {
        LiveStatus.update { it.copy(dongle = state, message = message) }
        if (message.isNotEmpty()) EventLog.log(message)
        updateNotification()
    }

    // ---------------------------------------------------------------- notification

    private fun goForeground(text: String): Boolean = try {
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (e: Exception) {
        EventLog.log("Foreground start refused: ${e.javaClass.simpleName} ${e.message}")
        stopSelf()
        false
    }

    // This notification only exists while the service runs, i.e. while you're in the car (or
    // right after tapping Read now); it disappears when the car's Bluetooth goes away.
    private fun notificationText(): String {
        val st = LiveStatus.current
        val s = settings.current
        val km = store.current.lastKm
        val odo = if (km > 0) NumberFormat.getIntegerInstance().format(s.toUploadValue(km)) + " " + s.uploadUnit.label else "—"
        return when {
            st.phase == TripPhase.ACTIVE -> {
                val driven = if (st.tripStartKm > 0 && km > 0) km - st.tripStartKm else 0
                val mins = ((System.currentTimeMillis() - st.tripSince) / 60_000).coerceAtLeast(0)
                "+${TripNotifier.distance(driven, s.uploadUnit)} · $mins min · $odo"
            }
            st.dongle == DongleState.CONNECTED -> "Connected to the dongle · $odo"
            else -> st.dongle.label
        }
    }

    private fun notificationTitle(): String =
        if (LiveStatus.current.phase == TripPhase.ACTIVE) "Trip in progress" else "OdoSync"

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, OdoSyncApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notificationTitle())
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    private var lastNotifText = ""

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        val text = notificationTitle() + notificationText()
        if (text == lastNotifText) return
        lastNotifText = text
        if (stopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(notificationText()))
    }
}
