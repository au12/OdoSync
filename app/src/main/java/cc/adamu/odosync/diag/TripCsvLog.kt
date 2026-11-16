package cc.adamu.odosync.diag

import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/**
 * Per-drive data log, one row per DME poll with every value carried forward. Plain CSV with a
 * time column in seconds, so it opens in a spreadsheet, Glovebox's attachment viewer or
 * datazap.me.
 */
class TripCsvLog(val file: File, private val t0: Long) {
    private var out: BufferedWriter? = null
    private var rows = 0
    private var lastRowT = 0L

    private val columns: List<Pair<String, (Map<Sig, Double>) -> Double?>> = listOf(
        "boost_kPa" to { v -> v[Sig.BOOST] },
        "boost_target_kPa" to { v -> v[Sig.BOOST_TARGET] },
        "baro_kPa" to { v -> v[Sig.BARO] },
        "boost_psi" to { v -> v[Sig.BOOST]?.let { b -> (b - (v[Sig.BARO] ?: 101.3)) * Units.KPA_TO_PSI } },
        "boost_target_psi" to { v -> v[Sig.BOOST_TARGET]?.let { b -> (b - (v[Sig.BARO] ?: 101.3)) * Units.KPA_TO_PSI } },
        "timing_1" to { v -> v[Sig.TIMING_1] },
        "timing_2" to { v -> v[Sig.TIMING_2] },
        "timing_3" to { v -> v[Sig.TIMING_3] },
        "timing_4" to { v -> v[Sig.TIMING_4] },
        "timing_5" to { v -> v[Sig.TIMING_5] },
        "timing_6" to { v -> v[Sig.TIMING_6] },
        "iat_C" to { v -> v[Sig.IAT] },
        "oil_C" to { v -> v[Sig.OIL] },
        "coolant_C" to { v -> v[Sig.COOLANT] },
        "rail_bar" to { v -> v[Sig.RAIL] },
        "low_fuel_bar" to { v -> v[Sig.LOW_FUEL] },
        "lambda" to { v -> v[Sig.LAMBDA] },
        "speed_kmh" to { v -> v[Sig.SPEED] },
    )

    fun open() {
        file.parentFile?.mkdirs()
        out = file.bufferedWriter().also {
            it.write("time_s," + columns.joinToString(",") { c -> c.first } + ",in_boost\n")
        }
    }

    /** Append a row with the latest value of everything, at most every 100 ms. */
    fun row(t: Long, latest: Map<Sig, Double>, inBoost: Boolean) {
        val w = out ?: return
        if (t - lastRowT < 100) return
        lastRowT = t
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "%.2f", (t - t0) / 1000.0))
        for ((_, f) in columns) {
            sb.append(',')
            f(latest)?.let { sb.append(String.format(Locale.US, "%.2f", it)) }
        }
        sb.append(',').append(if (inBoost) 1 else 0).append('\n')
        runCatching { w.write(sb.toString()) }
        if (++rows % 50 == 0) runCatching { w.flush() }
    }

    val rowCount: Int get() = rows

    fun close() {
        runCatching { out?.flush(); out?.close() }
        out = null
    }
}
