package cc.adamu.odosync.data

import android.content.Context
import android.content.SharedPreferences
import cc.adamu.odosync.diag.HealthThresholds
import cc.adamu.odosync.diag.Units
import cc.adamu.odosync.link.AdapterKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToLong

enum class AuthMode { API_KEY, BASIC }
enum class UploadUnit(val label: String) { MILES("mi"), KILOMETERS("km") }

/** How drive summaries are written to LubeLogger/Glovebox notes. */
enum class NoteMode(val label: String) { DAILY("One note per day"), PER_TRIP("One note per drive") }

data class SettingsSnapshot(
    val baseUrl: String = "",
    val authMode: AuthMode = AuthMode.API_KEY,
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
    val vehicleId: Int = 0,
    val vehicleName: String = "",
    val dongleMac: String? = null,
    val dongleName: String? = null,
    val dongleNamePattern: String = DEFAULT_NAME_PATTERN,
    val carBtMacs: Set<String> = emptySet(),
    val useAndroidAuto: Boolean = true,
    val voltageThreshold: Float = 13.1f,
    val uploadUnit: UploadUnit = UploadUnit.MILES,
    val offset: Int = 0,
    val minDelta: Int = 1,
    val tag: String = "obd",
    val notes: String = "Auto: OBD odometer (OdoSync)",
    val wifiOnly: Boolean = false,
    val odoSource: String? = null,
    val bmwVariableDlc: Boolean = false,

    // Adapter
    val adapterKind: AdapterKind = AdapterKind.VEEPEAK_BLE,
    val wifiHost: String = "192.168.0.10",
    val wifiPort: Int = 35000,

    // Engine health
    val healthEnabled: Boolean = true,
    val healthFastSampling: Boolean = true,
    val healthUnits: Units = Units.US,
    val thresholds: HealthThresholds = HealthThresholds(),
    val notesEnabled: Boolean = true,
    val noteMode: NoteMode = NoteMode.DAILY,
    val healthNote: Boolean = true,
    val attachLogs: Boolean = true,
    val haUrl: String = "",
    val haToken: String = "",
    val haPrefix: String = "odosync",
    val haEnabled: Boolean = true,
) {
    val lubeLoggerConfigured: Boolean get() = baseUrl.isNotBlank() && vehicleId != 0 &&
        (if (authMode == AuthMode.API_KEY) apiKey.isNotBlank() else username.isNotBlank())

    /** The adapter can be reached: a paired BLE dongle, or a host for Wi-Fi. */
    val adapterConfigured: Boolean get() = if (adapterKind.isBle) dongleMac != null else wifiHost.isNotBlank()

    val homeAssistantConfigured: Boolean get() = haEnabled && haUrl.isNotBlank() && haToken.isNotBlank()

    /** Entity-id prefix, cleaned to what Home Assistant accepts. */
    val haEntityPrefix: String get() =
        haPrefix.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_').ifBlank { "odosync" }

    /** Converts the car's internal kilometre count into the value LubeLogger should store. */
    fun toUploadValue(km: Long): Long = when (uploadUnit) {
        UploadUnit.MILES -> (km * KM_TO_MI).roundToLong() + offset
        UploadUnit.KILOMETERS -> km + offset
    }

    companion object {
        const val KM_TO_MI = 0.621371
        const val DEFAULT_NAME_PATTERN = "(?i).*(veepeak|obd|elm|vlink|wican).*"
    }
}

/** Plain SharedPreferences: synchronous reads are handy from receivers and the service. */
class Settings private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<SettingsSnapshot> = _flow.asStateFlow()
    val current: SettingsSnapshot get() = _flow.value

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _flow.value = read() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun read(): SettingsSnapshot = SettingsSnapshot(
        baseUrl = prefs.getString("baseUrl", "")!!,
        authMode = runCatching { AuthMode.valueOf(prefs.getString("authMode", AuthMode.API_KEY.name)!!) }
            .getOrDefault(AuthMode.API_KEY),
        apiKey = prefs.getString("apiKey", "")!!,
        username = prefs.getString("username", "")!!,
        password = prefs.getString("password", "")!!,
        vehicleId = prefs.getInt("vehicleId", 0),
        vehicleName = prefs.getString("vehicleName", "")!!,
        dongleMac = prefs.getString("dongleMac", null),
        dongleName = prefs.getString("dongleName", null),
        dongleNamePattern = prefs.getString("dongleNamePattern", SettingsSnapshot.DEFAULT_NAME_PATTERN)!!,
        carBtMacs = prefs.getStringSet("carBtMacs", emptySet())!!.toSet(),
        useAndroidAuto = prefs.getBoolean("useAndroidAuto", true),
        voltageThreshold = prefs.getFloat("voltageThreshold", 13.1f),
        uploadUnit = runCatching { UploadUnit.valueOf(prefs.getString("uploadUnit", UploadUnit.MILES.name)!!) }
            .getOrDefault(UploadUnit.MILES),
        offset = prefs.getInt("offset", 0),
        minDelta = prefs.getInt("minDelta", 1),
        tag = prefs.getString("tag", "obd")!!,
        notes = prefs.getString("notes", "Auto: OBD odometer (OdoSync)")!!,
        wifiOnly = prefs.getBoolean("wifiOnly", false),
        odoSource = prefs.getString("odoSource", null),
        bmwVariableDlc = prefs.getBoolean("bmwVariableDlc", false),
        adapterKind = AdapterKind.from(prefs.getString("adapterKind", null)),
        wifiHost = prefs.getString("wifiHost", "192.168.0.10")!!,
        wifiPort = prefs.getInt("wifiPort", 35000),
        healthEnabled = prefs.getBoolean("healthEnabled", true),
        healthFastSampling = prefs.getBoolean("healthFastSampling", true),
        healthUnits = Units.from(prefs.getString("healthUnits", null)),
        thresholds = HealthThresholds(
            pullEventDeg = prefs.getFloat("thPullEventDeg", 1.5f).toDouble(),
            severePullDeg = prefs.getFloat("thSeverePullDeg", 3.0f).toDouble(),
            underboostKpa = prefs.getFloat("thUnderboostKpa", 15f).toDouble(),
            overboostKpa = prefs.getFloat("thOverboostKpa", 20f).toDouble(),
            oilAlertC = prefs.getFloat("thOilAlertC", 130f).toDouble(),
            iatAlertC = prefs.getFloat("thIatAlertC", 65f).toDouble(),
        ),
        notesEnabled = prefs.getBoolean("notesEnabled", true),
        noteMode = runCatching { NoteMode.valueOf(prefs.getString("noteMode", NoteMode.DAILY.name)!!) }
            .getOrDefault(NoteMode.DAILY),
        healthNote = prefs.getBoolean("healthNote", true),
        attachLogs = prefs.getBoolean("attachLogs", true),
        haUrl = prefs.getString("haUrl", "")!!,
        haToken = prefs.getString("haToken", "")!!,
        haPrefix = prefs.getString("haPrefix", "odosync")!!,
        haEnabled = prefs.getBoolean("haEnabled", true),
    )

    fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _flow.value = read()
    }

    companion object {
        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(context.applicationContext).also { instance = it }
        }
    }
}
