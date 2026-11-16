package cc.adamu.odosync.link

import cc.adamu.odosync.data.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ELM327 over TCP, as served by a WiCAN Pro in "ELM327" mode (port 35000; 192.168.0.10 on its own
 * access point, or whatever your router hands it in station mode).
 *
 * Groundwork for moving the 435i to a WiCAN Pro. One thing to know: wireless Android Auto keeps
 * the phone's Wi-Fi joined to the head unit/adapter, so while AA is projecting the phone can't
 * also be on the WiCAN's network. In the car the WiCAN Pro's Bluetooth LE side
 * ([AdapterKind.WICAN_PRO_BLE]) is the one that works next to AA; Wi-Fi suits a car parked in
 * range of the house network, or a setup without wireless AA.
 */
class TcpLink(
    private val host: String,
    private val port: Int,
) : ObdLink {

    override val label: String get() = "Wi-Fi $host:$port"

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val rx = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> get() = rx

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    private var reader: Job? = null

    suspend fun connect(timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        close()
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            socket = s
            output = s.getOutputStream()
            _connected.value = true
            reader = scope.launch { pump(s.getInputStream()) }
            EventLog.log("Wi-Fi: connected to $host:$port")
            true
        } catch (e: Exception) {
            EventLog.log("Wi-Fi: can't reach $host:$port (${e.javaClass.simpleName}: ${e.message})")
            close()
            false
        }
    }

    private fun pump(input: InputStream) {
        val buf = ByteArray(512)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) rx.trySend(buf.copyOf(n))
            }
        } catch (_: Exception) {
            // Socket closed or dropped; fall through.
        }
        if (_connected.value) EventLog.log("Wi-Fi: connection to $host:$port closed")
        _connected.value = false
    }

    override suspend fun write(text: String): Boolean = writeMutex.withLock {
        val out = output ?: return false
        withContext(Dispatchers.IO) {
            try {
                out.write(text.toByteArray(Charsets.US_ASCII))
                out.flush()
                true
            } catch (e: Exception) {
                _connected.value = false
                false
            }
        }
    }

    override fun drain() {
        while (rx.tryReceive().isSuccess) { /* discard */ }
    }

    override fun close() {
        reader?.cancel()
        reader = null
        runCatching { socket?.close() }
        socket = null
        output = null
        _connected.value = false
    }

    /** Releases the reader coroutine for good. */
    fun dispose() {
        close()
        scope.cancel()
    }
}
