package com.otabi.doorman.platform

import com.otabi.doorman.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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
        scope.launch(Dispatchers.IO) {
            bluetoothManager.scanForService(serviceUuid).collect { ad ->
                _discoveredDevices[ad.macAddress] = ad
                
                if (ad.macAddress.equals(macAddress, ignoreCase = true)) {
                    val status = protocol.parseStatus(ad.manufacturerData)
                    if (status != DoorStatus.UNKNOWN) {
                        stateTracker.updateHardwareState(status)
                    }
                }
            }
        }
    }

    suspend fun connect(): Result<Unit> {
        if (connection != null) return Result.success(Unit)
        
        val result = bluetoothManager.connect(macAddress)
        return result.map { conn ->
            connection = conn
            scope.launch(Dispatchers.IO) {
                conn.subscribeToNotifications(serviceUuid, txCharUuid).collect {
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

    override suspend fun getStatus(address: String?): Result<DoorStatus> {
        return Result.success(stateTracker.syntheticState.value)
    }

    override suspend fun openDoor(address: String?): Result<Unit> {
        val current = stateTracker.syntheticState.value
        if (current == DoorStatus.OPEN || current == DoorStatus.OPENING) {
            return Result.success(Unit) // Already in requested state
        }
        return toggleDoor()
    }

    override suspend fun closeDoor(address: String?): Result<Unit> {
        val current = stateTracker.syntheticState.value
        if (current == DoorStatus.CLOSED || current == DoorStatus.CLOSING) {
            return Result.success(Unit) // Already in requested state
        }
        return toggleDoor()
    }

    private suspend fun toggleDoor(): Result<Unit> {
        val conn = connection ?: return Result.failure(Exception("Not connected. Please connect first."))
        val payload = protocol.buildToggleCommand()
        return conn.writeCharacteristic(serviceUuid, rxCharUuid, payload)
    }
}