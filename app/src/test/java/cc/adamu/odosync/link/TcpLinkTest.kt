package cc.adamu.odosync.link

import cc.adamu.odosync.obd.Elm327
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** ELM327 over TCP, the way a WiCAN Pro serves it on port 35000. */
class TcpLinkTest {

    @Test fun elmOverWifi() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.accept().use { s ->
                val input = s.getInputStream()
                val out = s.getOutputStream()
                val cmd = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    if (b.toChar() != '\r') { cmd.append(b.toChar()); continue }
                    val reply = when (cmd.toString().uppercase()) {
                        "ATZ" -> "\r\rELM327 v2.3"
                        "ATI" -> "ELM327 v2.3"
                        "ATRV" -> "12.6V"
                        else -> "OK"
                    }
                    cmd.clear()
                    // Answer in two TCP chunks, like a real adapter often does.
                    out.write(reply.take(2).toByteArray()); out.flush()
                    out.write((reply.drop(2) + "\r\r>").toByteArray()); out.flush()
                }
            }
        }
        val link = TcpLink("127.0.0.1", server.localPort)
        assertTrue(link.connect(2000))
        val elm = Elm327(link)
        assertTrue(elm.init())
        assertEquals("ELM327 v2.3", elm.version)
        assertEquals(12.6f, elm.voltage()!!, 0.001f)
        link.dispose()
        assertFalse(link.connected.value)
        server.close()
    }

    @Test fun unreachableHostFailsCleanly() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // closed again: nothing listens
        val link = TcpLink("127.0.0.1", port)
        assertFalse(link.connect(1000))
        assertFalse(link.connected.value)
    }
}
