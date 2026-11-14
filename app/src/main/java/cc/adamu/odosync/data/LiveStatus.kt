package cc.adamu.odosync.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DongleState(val label: String) {
    NOT_PAIRED("Not paired"),
    STOPPED("Service idle"),
    CONNECTING("Connecting…"),
    WAITING("Waiting for dongle"),
    INITIALISING("Initialising ELM327…"),
    CONNECTED("Connected"),
    ERROR("Error"),
}

enum class TripPhase(val label: String) {
    IDLE("Idle"),
    WATCHING("Watching for ignition"),
    ACTIVE("Trip in progress"),
}

/** In-memory, process-wide status the service publishes and the UI renders. */
data class Status(
    val serviceRunning: Boolean = false,
    val dongle: DongleState = DongleState.STOPPED,
    val elmVersion: String = "",
    val androidAuto: Boolean = false,
    val carBluetooth: Boolean = false,
    val voltage: Float? = null,
    val phase: TripPhase = TripPhase.IDLE,
    val tripSince: Long = 0,
    val tripStartKm: Long = -1,
    val engineRpm: Int? = null,
    val lastPollAt: Long = 0,
    val message: String = "",
)

object LiveStatus {
    private val _flow = MutableStateFlow(Status())
    val flow: StateFlow<Status> = _flow.asStateFlow()
    val current: Status get() = _flow.value
    fun update(block: (Status) -> Status) = _flow.update(block)
}
