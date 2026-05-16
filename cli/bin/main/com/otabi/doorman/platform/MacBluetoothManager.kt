package com.otabi.doorman.platform

import com.otabi.doorman.domain.BleDeviceAdvertisement
import com.otabi.doorman.domain.BluetoothConnection
import com.otabi.doorman.domain.BluetoothManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer

class MacBluetoothManager(
    private val host: String = "127.0.0.1",
    private val port: Int = 9000
) : BluetoothManager {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val _advertisements = MutableSharedFlow<BleDeviceAdvertisement>(extraBufferCapacity = 64)
    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private var readerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Synchronized
    private fun ensureConnected() {
        if (socket?.isConnected == true && socket?.isClosed == false) return
        try {
            socket = Socket(host, port)
            input = DataInputStream(socket!!.getInputStream())
            output = DataOutputStream(socket!!.getOutputStream())

            readerJob?.cancel()
            readerJob = scope.launch {
                try {
                    while (isActive) {
                        val msg = readLengthPrefixedMessage(input!!) ?: break
                        val mtype = msg[0].toInt() and 0xFF
                        val payload = msg.copyOfRange(1, msg.size)
                        
                        when (mtype) {
                            0x02 -> { // NOTIFY
                                _notifications.emit(payload)
                            }
                            0x03 -> { // SCAN
                                try {
                                    var offset = 0
                                    val macLen = payload[offset++].toInt() and 0xFF
                                    val macStr = String(payload, offset, macLen, Charsets.UTF_8)
                                    offset += macLen

                                    val rssi = payload[offset++].toInt()

                                    val nameLen = payload[offset++].toInt() and 0xFF
                                    val nameStr = String(payload, offset, nameLen, Charsets.UTF_8)
                                    offset += nameLen

                                    val mfrLen = payload[offset++].toInt() and 0xFF
                                    val mfrBytes = payload.copyOfRange(offset, offset + mfrLen)

                                    _advertisements.emit(
                                        BleDeviceAdvertisement(
                                            macAddress = macStr,
                                            name = nameStr,
                                            manufacturerData = mfrBytes,
                                            rssi = rssi
                                        )
                                    )
                                } catch (e: Exception) {
                                    // ignore malformed packets
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    socket?.close()
                }
            }
        } catch (e: Exception) {
            println("Failed to connect to MacBluetoothManager Daemon: ${e.message}")
        }
    }

    private fun readLengthPrefixedMessage(input: DataInputStream): ByteArray? {
        return try {
            val lenBytes = ByteArray(4)
            input.readFully(lenBytes)
            val len = ByteBuffer.wrap(lenBytes).int
            val body = ByteArray(len)
            input.readFully(body)
            body
        } catch (e: Exception) { null }
    }

    private fun sendTcpMessage(type: Int, payload: ByteArray = ByteArray(0)) {
        ensureConnected()
        val body = ByteArray(1 + payload.size)
        body[0] = type.toByte()
        System.arraycopy(payload, 0, body, 1, payload.size)
        val buf = ByteBuffer.allocate(4 + body.size)
        buf.putInt(body.size)
        buf.put(body)
        output?.write(buf.array())
        output?.flush()
    }

    override fun scanForService(serviceUuid: String): Flow<BleDeviceAdvertisement> {
        ensureConnected()
        return _advertisements.asSharedFlow()
    }

    override fun stopScan() {
        // Not required for daemon passthrough
    }

    override suspend fun connect(macAddress: String): Result<BluetoothConnection> {
        return withContext(Dispatchers.IO) {
            try {
                ensureConnected()
                sendTcpMessage(0x04, macAddress.toByteArray(Charsets.UTF_8))
                Result.success(MacBluetoothConnection(macAddress))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    inner class MacBluetoothConnection(
        private val macAddress: String
    ) : BluetoothConnection {
        override suspend fun writeCharacteristic(
            serviceUuid: String,
            characteristicUuid: String,
            payload: ByteArray
        ): Result<Unit> = withContext(Dispatchers.IO) {
            try {
                sendTcpMessage(0x01, payload)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        override suspend fun readCharacteristic(
            serviceUuid: String,
            characteristicUuid: String
        ): Result<ByteArray> {
            return Result.failure(UnsupportedOperationException("Read not supported"))
        }

        override fun subscribeToNotifications(
            serviceUuid: String,
            characteristicUuid: String
        ): Flow<ByteArray> {
            return _notifications.asSharedFlow()
        }

        override fun disconnect() {
            try {
                sendTcpMessage(0x05)
            } catch (e: Exception) {}
        }
    }
}