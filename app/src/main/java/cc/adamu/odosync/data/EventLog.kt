package cc.adamu.odosync.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent event log so you can see after a drive exactly what happened
 * (connects, ELM replies, trip start/end, uploads). Viewable/shareable from the app.
 */
object EventLog {
    private const val MAX_LINES = 400
    private const val MAX_FILE_BYTES = 256 * 1024
    private val lines = ArrayDeque<String>()
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow.asStateFlow()
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "odosync.log")
        file = f
        if (f.exists()) {
            if (f.length() > MAX_FILE_BYTES) {
                val keep = f.readLines().takeLast(MAX_LINES)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
            f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
            _flow.value = lines.toList()
        }
    }

    @Synchronized
    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        Log.i("OdoSync", msg)
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        _flow.value = lines.toList()
        runCatching { file?.appendText(line + "\n") }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        _flow.value = emptyList()
        runCatching { file?.writeText("") }
    }

    fun dump(): String = lines.joinToString("\n")
}
