package com.otabi.doorman.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SwitchBotDoorController(
    private val bluetoothManager: BluetoothManager,
    private val macAddress: String,
    private val protocol: SwitchBotProtocol,
    private val cipher: AesCtr,
    private val scope: CoroutineScope,
    private val travelTimeMs: Long = 15000L
) : DoorController {

    private val stateTracker = DoorStateTracker(
        scope          = scope,
        travelTimeMs   = travelTimeMs,
        failsafeTimeMs = travelTimeMs + travelTimeMs / 2
    )

    override val state: StateFlow<DoorStatus> = stateTracker.syntheticState

    private var connection: BluetoothConnection? = null
    override val isConnected: Boolean get() = connection != null

    private val _discoveredDevices = mutableListOf<BleDeviceAdvertisement>()
    override val discoveredDevices: List<BleDeviceAdvertisement> get() = _discoveredDevices.toList()

    private var scanJob: Job? = null
    private var notifyJob: Job? = null

    init {
        scanJob = scope.launch {
            bluetoothManager.scanForService(SERVICE_UUID).collect { adv ->
                val idx = _discoveredDevices.indexOfFirst { it.macAddress == adv.macAddress }
                if (idx >= 0) _discoveredDevices[idx] = adv else _discoveredDevices.add(adv)

                // State is broadcast continuously in advertisement: byte 6, LSB 0=CLOSED 1=OPEN
                if (adv.macAddress.equals(macAddress, ignoreCase = true) && adv.manufacturerData.size > 6) {
                    val status = if (adv.manufacturerData[6].toInt() and 0x01 == 1) DoorStatus.CLOSED else DoorStatus.OPEN
                    stateTracker.updateHardwareState(status)
                }
            }
        }
    }

    override suspend fun connect(): Result<Unit> {
        val result = bluetoothManager.connect(macAddress)
        if (result.isFailure) return Result.failure(result.exceptionOrNull()!!)

        connection = result.getOrThrow()

        notifyJob?.cancel()
        notifyJob = scope.launch {
            connection!!.subscribeToNotifications(SERVICE_UUID, NOTIFY_UUID).collect { data ->
                val decrypted = cipher.decrypt(data)        
                val status = protocol.parseNotification(decrypted)
                if (status != DoorStatus.UNKNOWN) stateTracker.updateHardwareState(status)
            }
        }

        return Result.success(Unit)
    }

    private suspend fun sendToggle(
        conn: BluetoothConnection,
        blockedStates: Set<DoorStatus>,
        blockedMessage: (DoorStatus) -> String
    ): Result<Unit> {
        val current = state.value
        if (current in blockedStates)
            return Result.failure(Exception(blockedMessage(current)))
        val encrypted = cipher.encrypt(TOGGLE_COMMAND)
        val result = conn.writeCharacteristic(SERVICE_UUID, WRITE_UUID, encrypted)
        if (result.isSuccess) stateTracker.onCommandAcknowledged()
        return result
    }

    override suspend fun openDoor(address: String?): Result<Unit> {
        val conn = connection ?: return Result.failure(Exception("Not connected. Call connect() first."))
        return sendToggle(conn, setOf(DoorStatus.OPEN, DoorStatus.OPENING)) { "Door is already $it" }
    }

    override suspend fun closeDoor(address: String?): Result<Unit> {
        val conn = connection ?: return Result.failure(Exception("Not connected. Call connect() first."))
        return sendToggle(conn, setOf(DoorStatus.CLOSED, DoorStatus.CLOSING)) { "Door is already $it" }
    }

    override suspend fun getStatus(address: String?): Result<DoorStatus> =
        Result.success(state.value)

    override fun disconnect() {
        notifyJob?.cancel()
        notifyJob = null
        connection?.disconnect()
        connection = null
    }

    companion object {
        const val SERVICE_UUID  = "cba20d00-224d-11e6-9fb8-0002a5d5c51b"
        const val WRITE_UUID    = "cba20002-224d-11e6-9fb8-0002a5d5c51b"
        const val NOTIFY_UUID   = "cba20003-224d-11e6-9fb8-0002a5d5c51b"

        private val TOGGLE_COMMAND = byteArrayOf(0x57, 0x01, 0x01)
    }
}
