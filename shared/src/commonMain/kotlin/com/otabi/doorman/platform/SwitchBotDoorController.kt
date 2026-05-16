package com.otabi.doorman.platform

import com.otabi.doorman.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Controller for a SwitchBot Relay‑based Garage Door Add‑On.
 *
 * Uses the classic unencrypted UART protocol:
 *   - TX notifications contain door sensor state
 *   - RX writes send raw frames (e.g., 57 <len> <cmd> <payload> <checksum>)
 */
class SwitchBotDoorController(
    private val bluetoothManager: BluetoothManager,
    private val macAddress: String,
    private val protocol: SwitchBotProtocol,
    private val scope: CoroutineScope,
    private val travelTimeMs: Long = 15000L
) : DoorController {

    private val stateTracker = DoorStateTracker(scope, travelTimeMs)
    override val state: StateFlow<DoorStatus> = stateTracker.syntheticState

    private val _discoveredDevices = ConcurrentHashMap<String, BleDeviceAdvertisement>()
    val discoveredDevices: List<BleDeviceAdvertisement>
        get() = _discoveredDevices.values.toList()

    private var connection: BluetoothConnection? = null
    override val isConnected: Boolean
        get() = connection != null

    private val serviceUuid = "cba20d00-224d-11e6-9fb8-0002a5d5c51b"
    private val rxCharUuid = "cba20002-224d-11e6-9fb8-0002a5d5c51b"
    private val txCharUuid = "cba20003-224d-11e6-9fb8-0002a5d5c51b"

    init {
        // BLE scan loop
        scope.launch(Dispatchers.IO) {
            bluetoothManager.scanForService(serviceUuid).collect { ad ->
                _discoveredDevices[ad.macAddress] = ad

                // Only use manufacturer data for coarse state (optional)
                if (ad.macAddress.equals(macAddress, ignoreCase = true)) {
                    val status = protocol.parseStatus(ad.manufacturerData)
                    if (status != DoorStatus.UNKNOWN) {
                        stateTracker.updateHardwareState(status)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------

    suspend fun connect(): Result<Unit> {
        if (connection != null) return Result.success(Unit)

        val result = bluetoothManager.connect(macAddress)
        return result.map { conn ->
            connection = conn

            // Subscribe to TX notifications (door sensor state)
            scope.launch(Dispatchers.IO) {
                conn.subscribeToNotifications(serviceUuid, txCharUuid).collect { data ->
                    // Real device sends raw UART notifications
                    val status = protocol.parseNotification(data)
                    if (status != DoorStatus.UNKNOWN) {
                        stateTracker.updateHardwareState(status)
                    }

                    // Acknowledge command completion
                    stateTracker.onCommandAcknowledged()
                }
            }

            Unit
        }
    }

    fun disconnect() {
        connection?.disconnect()
        connection = null
    }

    // -------------------------------------------------------------------------
    // DoorController API
    // -------------------------------------------------------------------------

    override suspend fun getStatus(address: String?): Result<DoorStatus> {
        return Result.success(stateTracker.syntheticState.value)
    }

    override suspend fun openDoor(address: String?): Result<Unit> {
        val current = stateTracker.syntheticState.value
        if (current == DoorStatus.OPEN || current == DoorStatus.OPENING) {
            return Result.success(Unit)
        }
        return toggleDoor()
    }

    override suspend fun closeDoor(address: String?): Result<Unit> {
        val current = stateTracker.syntheticState.value
        if (current == DoorStatus.CLOSED || current == DoorStatus.CLOSING) {
            return Result.success(Unit)
        }
        return toggleDoor()
    }

    private suspend fun toggleDoor(): Result<Unit> {
        val conn = connection ?: return Result.failure(Exception("Not connected. Please connect first."))

        // Build raw UART frame (no encryption)
        val payload = protocol.buildToggleCommand()

        return conn.writeCharacteristic(serviceUuid, rxCharUuid, payload)
    }
}
