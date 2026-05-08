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
                    val ok = socketClient!!.connect(selector)
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

    /**
     * Domain API required by BluetoothManager.
     * Discover devices and return a Result<List<BluetoothDevice>>.
     *
     * Current implementation returns an empty list in simulator mode to satisfy
     * the interface and allow compilation. Replace with real discovery logic
     * (mapping socket daemon scan results to BluetoothDevice) when available.
     */
    override suspend fun discoverDevices(): Result<List<BluetoothDevice>> {
        return withContext(Dispatchers.IO) {
            if (USE_SOCKET_DAEMON) {
                // Simulator: no active scan implemented here; return empty list.
                // If you want discovery via the daemon, implement a DISCOVER message
                // in the socket protocol and map results to BluetoothDevice.
                Result.success(emptyList())
            } else {
                // TODO: implement real macOS discovery and map to domain BluetoothDevice
                Result.failure(Exception("Real macOS discovery not implemented"))
            }
        }
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
