package com.otabi.doorman.platform

import com.otabi.doorman.domain.BluetoothManager
import com.otabi.doorman.domain.BluetoothDevice
import com.otabi.doorman.domain.BluetoothConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MacBluetoothManager
 *
 * Platform adapter for macOS. Default behavior uses the socket daemon (simulator).
 * This class implements the domain BluetoothManager interface methods required
 * by the compiler: connect(...) and discoverDevices().
 *
 * Notes:
 * - Place BleSocketClient.kt in the same package (com.otabi.doorman.platform).
 * - This implementation returns safe Result values so the class is concrete.
 * - Replace the stubbed Result.failure/success returns with real adapters when
 *   you provide the domain BluetoothConnection factory/constructor.
 */
class MacBluetoothManager(
    private val host: String = "127.0.0.1",
    private val port: Int = 9000,
    private val selector: String = "SwitchBot-KATA"
) : BluetoothManager {

    // Toggle: use the socket daemon by default for tests/CI.
    private val USE_SOCKET_DAEMON: Boolean = System.getenv("USE_SOCKET_DAEMON")?.toBoolean() ?: true

    // Socket client (lazy) used when USE_SOCKET_DAEMON == true
    private var socketClient: BleSocketClient? = null

    // Connection state
    private val connected = AtomicBoolean(false)

    // Callbacks set by domain layer (optional helpers)
    private var notifyCallback: ((ByteArray) -> Unit)? = null
    private var writeAckCallback: ((ByteArray) -> Unit)? = null

    /**
     * Domain API required by BluetoothManager.
     * Attempt to connect to the socket daemon and return a Result.
     *
     * IMPORTANT: This returns a failure Result for BluetoothConnection because
     * the concrete BluetoothConnection construction is domain-specific.
     * Replace the failure with Result.success(yourConnectionAdapter) once you
     * implement an adapter that wraps socketClient into a BluetoothConnection.
     */
    override suspend fun connect(device: BluetoothDevice): Result<BluetoothConnection> {
        return withContext(Dispatchers.IO) {
            if (USE_SOCKET_DAEMON) {
                try {
                    socketClient = BleSocketClient(host, port)
                    val ok = socketClient!!.connect(device.address)
                    if (!ok) {
                        connected.set(false)
                        return@withContext Result.failure<BluetoothConnection>(Exception("Socket client failed to connect"))
                    }

                    // Wire callbacks
                    socketClient!!.onNotify = { payload -> notifyCallback?.invoke(payload) }
                    socketClient!!.onWriteAck = { ack -> writeAckCallback?.invoke(ack) }

                    connected.set(true)

                    // TODO: construct and return a domain BluetoothConnection adapter here.
                    // Example:
                    // val conn = SocketBluetoothConnectionAdapter(socketClient!!)
                    // return@withContext Result.success(conn)
                    return@withContext Result.failure<BluetoothConnection>(Exception("Connected to socket daemon; BluetoothConnection adapter not implemented"))
                } catch (ex: Exception) {
                    connected.set(false)
                    return@withContext Result.failure<BluetoothConnection>(ex)
                }
            } else {
                // TODO: Implement real macOS CoreBluetooth connection here and return a BluetoothConnection
                return@withContext Result.failure<BluetoothConnection>(Exception("Real macOS BLE not implemented"))
            }
        }
    }

    override suspend fun discoverDevices(): Result<List<BluetoothDevice>> {
        return withContext(Dispatchers.IO) {
            try {
                val client = socketClient ?: BleSocketClient(host, port)
                if (socketClient == null) socketClient = client

                // Ensure connected to daemon
                if (!connected.get()) {
                    client.connect(selector)
                    connected.set(true)
                }

                // This pulls the live scan results from the Python background task
                val (mtype, payload) = client.sendDiscoveryRequest()
                
                if (mtype.toInt() != 0x08) {
                    return@withContext Result.failure(Exception("Expected 0x08, got $mtype"))
                }

                val jsonString = String(payload, Charsets.UTF_8)
                Result.success(parseDiscoveryJson(jsonString))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun parseDiscoveryJson(json: String): List<BluetoothDevice> {
        // If you're using Gson or Kotlinx.Serialization, use that here.
        // If not, a quick manual parse for address and name:
        val devices = mutableListOf<BluetoothDevice>()
        val regex = """\{"address":\s*"([^"]+)",\s*"name":\s*"([^"]*)",\s*"rssi":\s*(-?\d+),\s*"adv":\s*"([^"]*)"\}""".toRegex()
        
        regex.findAll(json).forEach { match ->
            devices.add(BluetoothDevice(
                address = match.groupValues[1],
                name = match.groupValues[2].ifEmpty { null },
                rssi = match.groupValues[3].toInt(),
                advHex = match.groupValues[4]
            ))
        }
        return devices
    }
    // --- Helper methods (not part of the domain interface) ---

    /**
     * Subscribe to a notify characteristic (string UUID).
     * Domain code can call this via an adapter if needed.
     */
    fun subscribe(notifyUuid: String) {
        if (USE_SOCKET_DAEMON) {
            socketClient?.subscribe(notifyUuid)
        } else {
            // TODO: subscribe via CoreBluetooth and forward notifications to notifyCallback
        }
    }

    fun writeWithResponse(raw: ByteArray) {
        if (USE_SOCKET_DAEMON) {
            socketClient?.writeWithResponse(raw)
        } else {
            // TODO: write to GATT characteristic with response and invoke writeAckCallback when ATT write response arrives
        }
    }

    fun writeNoResponse(raw: ByteArray) {
        if (USE_SOCKET_DAEMON) {
            socketClient?.writeNoResponse(raw)
        } else {
            // TODO: write to GATT characteristic without response
        }
    }

    fun setNotifyCallback(cb: (ByteArray) -> Unit) {
        notifyCallback = cb
    }

    fun setWriteAckCallback(cb: (ByteArray) -> Unit) {
        writeAckCallback = cb
    }

    fun disconnect() {
        if (USE_SOCKET_DAEMON) {
            try {
                socketClient?.disconnect()
            } catch (_: Exception) { }
            connected.set(false)
        } else {
            // TODO: disconnect from CoreBluetooth peripheral
        }
    }

    fun isConnected(): Boolean = connected.get()
}
