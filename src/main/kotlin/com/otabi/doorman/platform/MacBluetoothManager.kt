package com.otabi.doorman.platform

import com.otabi.doorman.domain.BluetoothManager
import com.otabi.doorman.domain.BluetoothDevice
import com.otabi.doorman.domain.BluetoothConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

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
            if (!USE_SOCKET_DAEMON) return@withContext Result.failure(Exception("Not implemented"))

            try {
                // 1. Get or create the client
                val client = socketClient ?: BleSocketClient(host, port).also { socketClient = it }

                // 2. Refresh wiring EVERY call to avoid the "Discovery Leak"
                client.onNotify = { payload -> notifyCallback?.invoke(payload) }
                client.onWriteAck = { ack -> writeAckCallback?.invoke(ack) }

                // 3. Connect ONLY if the socket is actually dead
                if (!client.isConnected) {
                    val ok = client.connect(device.address)
                    if (!ok) return@withContext Result.failure(Exception("Daemon unreachable"))
                }

                Result.success(SocketConnectionAdapter(client))
            } catch (ex: Exception) {
                Result.failure(ex)
            }
        }
    }

    override suspend fun discoverDevices(): Result<List<BluetoothDevice>> {
        return withContext(Dispatchers.IO) {
            try {
                val client = socketClient ?: BleSocketClient(host, port)
                if (socketClient == null) socketClient = client

                if (!connected.get()) {
                    client.connect(selector)
                    connected.set(true)
                    wireCallbacks(client)
                }

                Result.success(discoveredDevices.values.toList())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun wireCallbacks(client: BleSocketClient) {
        client.onNotify = { payload -> 
            handlePotentialDiscovery(payload)
            notifyCallback?.invoke(payload) 
        }
        client.onWriteAck = { ack -> writeAckCallback?.invoke(ack) }
    }

    private fun handlePotentialDiscovery(payload: ByteArray) {
        val actualData = payload
        try {
            // A status frame is exactly 6 bytes, so anything longer is likely a discovery pipe string
            if (actualData.size > 6) { 
                val text = String(actualData, Charsets.UTF_8)
                val parts = text.split("|")
                if (parts.size >= 3) {
                    val addr = parts[0].trim()
                    val rssi = parts[1].trim().toIntOrNull() ?: 0
                    val name = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() && it != "Unknown" }
                    val adv = parts.getOrNull(3)?.trim() ?: ""
                    discoveredDevices[addr] = BluetoothDevice(addr, name, rssi, adv)
                }
            }
        } catch (e: Exception) { /* silently ignore unparseable payloads */ }
    }
    // --- Helper methods (not part of the domain interface) ---

    // Local cache of devices heard via asynchronous broadcasts
    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()

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