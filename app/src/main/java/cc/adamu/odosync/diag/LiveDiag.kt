package cc.adamu.odosync.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the Health tab shows while a drive is in progress. In memory only. */
data class LiveHealth(
    val active: Boolean = false,
    /** Discovering what the DME supports, before the first sample. */
    val learning: Boolean = false,
    val values: Map<Sig, Double> = emptyMap(),
    val inBoost: Boolean = false,
    val fast: Boolean = false,
    /** Per cylinder, degrees behind the median of the others (null = not monitored). */
    val pullNow: List<Double?> = List(6) { null },
    val pullEvents: Int = 0,
    val maxPullDeg: Double = 0.0,
    val boostSeconds: Double = 0.0,
    val pollsPerSecond: Double = 0.0,
    val note: String = "",
)

object LiveDiag {
    private val _flow = MutableStateFlow(LiveHealth())
    val flow: StateFlow<LiveHealth> = _flow.asStateFlow()
    val current: LiveHealth get() = _flow.value
    fun update(block: (LiveHealth) -> LiveHealth) = _flow.update(block)
    fun reset() { _flow.value = LiveHealth() }
}
