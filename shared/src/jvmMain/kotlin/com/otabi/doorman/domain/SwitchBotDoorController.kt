package com.otabi.doorman.domain

import com.otabi.doorman.domain.BluetoothManager
import com.otabi.doorman.domain.DoorController
import com.otabi.doorman.domain.DoorStatus
import java.io.File
import java.util.Properties
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class SwitchBotDoorController(
    private val bluetoothManager: BluetoothManager
) : DoorController {

    override suspend fun openDoor(address: String?): Result<Unit> =
        sendAction(SwitchBotOpenerProtocol.openCommand, address)

    override suspend fun closeDoor(address: String?): Result<Unit> =
        sendAction(SwitchBotOpenerProtocol.closeCommand, address)

    override suspend fun getStatus(address: String?): Result<DoorStatus> {
        val device = resolveDevice(address) ?: return Result.failure(Exception("Device not found"))

        return try {
            val connection = bluetoothManager.connect(device).getOrThrow()

            if (bluetoothManager is MacBluetoothManager) {
                val deferredStatus = CompletableDeferred<DoorStatus>()

                try {
                    bluetoothManager.setNotifyCallback { payload ->
                        val frame =
                            if (payload.isNotEmpty() && payload[0] == 0x00.toByte())
                                payload.copyOfRange(1, payload.size)
                            else
                                payload

                        if (frame.size == 6) {
                            val stateCode = frame[4].toInt() and 0xFF
                            val parsedStatus = when (stateCode) {
                                0x00 -> DoorStatus.CLOSED
                                0x01 -> DoorStatus.OPEN
                                0x02 -> DoorStatus.OPENING
                                0x03 -> DoorStatus.CLOSING
                                else -> null
                            }
                            if (parsedStatus != null) {
                                deferredStatus.complete(parsedStatus)
                            }
                        }
                    }

                    connection.sendCommand(SwitchBotOpenerProtocol.statusCommand).getOrThrow()

                    val status = withTimeoutOrNull(5000L) { deferredStatus.await() }

                    if (status != null) Result.success(status)
                    else Result.failure(Exception("Timeout waiting for status notification"))
                } finally {
                    bluetoothManager.setNotifyCallback {}
                }
            } else {
                connection.sendCommand(SwitchBotOpenerProtocol.statusCommand).getOrThrow()
                Result.success(DoorStatus.UNKNOWN)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun sendAction(command: ByteArray, overrideAddr: String?): Result<Unit> {
        val device = resolveDevice(overrideAddr) ?: return Result.failure(Exception("SwitchBot opener not found"))

        val connection = bluetoothManager.connect(device).getOrElse {
            return Result.failure(it)
        }

        return try {
            connection.sendCommand(command)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun resolveDevice(overrideAddr: String?): BluetoothDevice? {
        return if (overrideAddr != null) {
            BluetoothDevice(overrideAddr, "WoSwitchGDO")
        } else {
            findDevice()
        }
    }

    private suspend fun findDevice(): BluetoothDevice? {
        val discovery = bluetoothManager.discoverDevices()
        val devices = discovery.getOrNull() ?: return null
        val configured = DeviceSecrets.switchBotMacAddress

        return if (configured != null) {
            devices.find { it.address.equals(configured, ignoreCase = true) }
        } else {
            devices.find { it.name?.contains("SwitchBot", ignoreCase = true) == true }
        }
    }
}

private object DeviceSecrets {
    private const val secretsFileName = "device-secrets.properties"
    private const val switchBotMacKey = "switchbot.mac.address"

    val switchBotMacAddress: String? by lazy {
        val file = File(secretsFileName)
        if (!file.exists()) return@lazy null
        val props = Properties().apply { file.inputStream().use { load(it) } }
        props.getProperty(switchBotMacKey)?.trim()?.takeIf { it.isNotBlank() }
    }
}

private object SwitchBotOpenerProtocol {
    val openCommand = hexStringToByteArray("570f31000000050102020000010000e5")
    val closeCommand = hexStringToByteArray("570f31000000050101020000010000e4")
    val statusCommand = hexStringToByteArray("570f31000000050100020000010000e3")
}
