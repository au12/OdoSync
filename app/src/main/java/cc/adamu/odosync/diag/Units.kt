package cc.adamu.odosync.diag

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** How engine-health numbers are shown and sent: US (psi, °F, mi) or metric (bar, °C, km). */
enum class Units(val label: String) {
    US("psi · °F · mi"),
    METRIC("bar · °C · km");

    val pressureUnit get() = if (this == US) "psi" else "bar"
    val tempUnit get() = if (this == US) "°F" else "°C"
    val distanceUnit get() = if (this == US) "mi" else "km"

    fun pressure(kPa: Double): Double = if (this == US) kPa * KPA_TO_PSI else kPa / 100.0
    fun temp(c: Double): Double = if (this == US) c * 9 / 5 + 32 else c
    fun distance(km: Double): Double = if (this == US) km * KM_TO_MI else km

    /** "14.2 psi" / "0.98 bar" (gauge pressure or a difference). */
    fun fmtPressure(kPa: Double?, signed: Boolean = false): String {
        if (kPa == null) return "—"
        val v = pressure(kPa)
        val digits = if (this == US) 1 else 2
        val body = String.format(Locale.US, "%.${digits}f", abs(v))
        val sign = when { !signed -> if (v < 0) "-" else ""; v > 0 -> "+"; v < 0 -> "−"; else -> "" }
        return "$sign$body $pressureUnit"
    }

    /** Temperature difference: a 10 °C rise is an 18 °F rise. */
    fun fmtTempDelta(c: Double?): String {
        if (c == null) return "—"
        val v = if (this == US) c * 9 / 5 else c
        return "${if (v >= 0) "+" else "−"}${abs(v).roundToInt()} $tempUnit"
    }

    fun fmtTemp(c: Double?): String = if (c == null) "—" else "${temp(c).roundToInt()} $tempUnit"

    fun fmtDistance(km: Double): String = String.format(Locale.US, "%.1f %s", distance(km), distanceUnit)

    companion object {
        const val KPA_TO_PSI = 0.1450377
        const val KM_TO_MI = 0.621371

        fun from(name: String?): Units = entries.firstOrNull { it.name == name } ?: US

        fun fmtDeg(d: Double?): String = if (d == null) "—" else String.format(Locale.US, "%.1f°", d)

        fun fmtBar(bar: Double?): String = if (bar == null) "—" else String.format(Locale.US, "%.0f bar", bar)
    }
}
