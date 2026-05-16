package com.otabi.doorman.platform

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/* BleSocketClient
 *
 * Minimal BLE-like client over the length-prefixed TCP socket façade used by the daemon.
 *
 * Framing: 4-byte big-endian length, 1-byte message type, then payload bytes.
 *
 * Message types:
 *  0x01 CONNECT
 *  0x02 CONNECT_RSP
 *  0x03 SUBSCRIBE
 *  0x04 SUBSCRIBE_RSP
 *  0x05 WRITE_REQ
 *  0x06 WRITE_RSP
 *  0x07 NOTIFY
 *  0x08 DISCONNECT
 *  0xFF ERROR
 *
 * Place this file at: src/main/kotlin/com/otabi/doorman/platform/BleSocketClient.kt
 */
class BleSocketClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 9000
) {
    private lateinit var socket: Socket
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    @Volatile private var running = false

    val isConnected: Boolean
    get() = if (::socket.isInitialized) {
        socket.isConnected && !socket.isClosed && running
    } else {
        false
    }

    /** Callbacks */
    var onNotify: ((ByteArray) -> Unit)? = null
    var onWriteAck: ((ByteArray) -> Unit)? = null
    var onSubscribeRsp: ((Boolean) -> Unit)? = null
    var onError: ((Int, String) -> Unit)? = null

    /**
     * Connect synchronously and read CONNECT_RSP.
     * Returns true on success.
     */
    fun connect(selector: String = "SwitchBot-KATA"): Boolean {
        // Defensive Cleanup: Close existing socket before opening a new one
        if (::socket.isInitialized) {
            try { 
                running = false
                socket.close() 
            } catch (_: Exception) {}
        }

        socket = Socket(host, port)
        input = socket.getInputStream()
        output = socket.getOutputStream()
        
        sendMessage(0x01.toByte(), selector.toByteArray(Charsets.UTF_8))
        
        val (t, payload) = readMessage()
        if (t.toInt() != 0x02) return false
        
        if (payload.isNotEmpty() && payload[0].toInt() == 0x00) {
            startReaderThread()
            return true
        }
        return false
    }           

    /**
     * Subscribe to a notify characteristic (string UUID).
     * Response handled asynchronously via onSubscribeRsp.
     */
    fun subscribe(charUuid: String) {
        sendMessage(0x03.toByte(), charUuid.toByteArray(Charsets.UTF_8))
    }

    /**
     * Write with response. raw is the exact bytes the Kotlin code would send to BLE.
     * The daemon will reply with WRITE_RSP (e.g., 02 00 00 00) which triggers onWriteAck.
     */
    fun writeWithResponse(raw: ByteArray) {
        val payload = ByteArray(1 + raw.size)
        payload[0] = 0x00
        System.arraycopy(raw, 0, payload, 1, raw.size)
        sendMessage(0x05.toByte(), payload)
    }

    /**
     * Write without response.
     */
    fun writeNoResponse(raw: ByteArray) {
        val payload = ByteArray(1 + raw.size)
        payload[0] = 0x01
        System.arraycopy(raw, 0, payload, 1, raw.size)
        sendMessage(0x05.toByte(), payload)
    }

    /**
     * Disconnect politely.
     */
    fun disconnect() {
        try {
            sendMessage(0x08.toByte(), ByteArray(0))
        } catch (_: Exception) { /* ignore */ }
        running = false
        try {
            socket.close()
        } catch (_: Exception) { /* ignore */ }
    }

    // --- Internal helpers ---

    private fun sendMessage(type: Byte, payload: ByteArray) {
        val data = ByteArray(1 + payload.size)
        data[0] = type
        System.arraycopy(payload, 0, data, 1, payload.size)
        val len = ByteBuffer.allocate(4).putInt(data.size).array()
        synchronized(output) {
            output.write(len)
            output.write(data)
            output.flush()
        }
    }

    private fun readMessage(): Pair<Byte, ByteArray> {
        val lenBuf = ByteArray(4)
        input.readFully(lenBuf)
        val mlen = ByteBuffer.wrap(lenBuf).int
        
        // Tightened Blast Shield: No GDO frame should exceed 64KB
        if (mlen < 0 || mlen > 65536) { 
            throw java.io.IOException("Corrupted frame: invalid length $mlen")
        }
        
        val buf = ByteArray(mlen)
        input.readFully(buf)
        val t = buf[0]
        val payload = if (buf.size > 1) buf.copyOfRange(1, buf.size) else ByteArray(0)
        return Pair(t, payload)
    }

    private fun startReaderThread() {
        running = true
        thread(start = true, isDaemon = true) {
            try {
                while (running) {
                    val (t, payload) = readMessage()
                    when (t.toInt()) {
                        0x04 -> { // SUBSCRIBE_RSP
                            val ok = payload.isNotEmpty() && payload[0].toInt() == 0x00
                            onSubscribeRsp?.invoke(ok)
                        }
                        0x06 -> { // WRITE_RSP
                            onWriteAck?.invoke(payload)
                        }
                        0x07 -> { // NOTIFY
                            onNotify?.invoke(payload)
                        }
                        0xFF -> { // ERROR
                            val code = if (payload.isNotEmpty()) payload[0].toInt() else -1
                            val msg = if (payload.size > 1) payload.copyOfRange(1, payload.size).toString(Charsets.UTF_8) else ""
                            onError?.invoke(code, msg)
                        }
                        else -> {
                            // ignore unknown types or extend as needed
                        }
                    }
                }
            } catch (e: Exception) {
                running = false
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) { /* ignore */ }
            }
        }
    }

    // InputStream helper to read fully (throws EOFException on premature EOF)
    private fun InputStream.readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = this.read(buf, off, buf.size - off)
            if (r < 0) throw java.io.EOFException()
            off += r
        }
    }
}

/**
 * Utility: convert hex string to byte array.
 * Accepts strings with or without spaces, upper/lower case.
 */
fun hexStringToByteArray(s: String): ByteArray {
    val clean = s.replace("\\s+".toRegex(), "")
    require(clean.length % 2 == 0) { "Hex string must have even length" }
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        out[i] = clean.substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }
    return out
}
