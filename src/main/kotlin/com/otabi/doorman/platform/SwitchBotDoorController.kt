package com.otabi.doorman.platform

import com.otabi.doorman.domain.BluetoothManager
import com.otabi.doorman.domain.BluetoothDevice
import com.otabi.doorman.domain.DoorController
import com.otabi.doorman.domain.DoorStatus
import java.io.File
import java.util.Properties

private object DeviceSecrets {
    private const val secretsFileName = "device-secrets.properties"
    private const val switchBotMacKey = "switchbot.mac.address"

    val switchBotMacAddress: String? by lazy {
        val file = File(secretsFileName)
        if (!file.exists()) return@lazy null

        val props = Properties().apply {
            file.inputStream().use { load(it) }
        }

        props.getProperty(switchBotMacKey)?.trim()?.takeIf { it.isNotBlank() }
    }
}

class SwitchBotDoorController(
    private val bluetoothManager: BluetoothManager
) : DoorController {

    override suspend fun openDoor(): Result<Unit> =
        sendAction(SwitchBotOpenerProtocol.openCommand)

    override suspend fun closeDoor(): Result<Unit> =
        sendAction(SwitchBotOpenerProtocol.closeCommand)

    override suspend fun getStatus(): Result<DoorStatus> {
        // Your current BLE backend cannot return status yet.
        return Result.success(DoorStatus.UNKNOWN)
    }

    private suspend fun sendAction(command: ByteArray): Result<Unit> {
        val device = findDevice() ?: return Result.failure(
            Exception("SwitchBot opener not found")
        )

        val connection = bluetoothManager.connect(device).getOrElse {
            return Result.failure(it)
        }

        return try {
            val writeResult = connection.sendCommand(command)
            if (writeResult.isFailure) {
                Result.failure(writeResult.exceptionOrNull() ?: Exception("Command failed"))
            } else {
                Result.success(Unit)
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun findDevice(): BluetoothDevice? {
        val discovery = bluetoothManager.discoverDevices()
        if (discovery.isFailure) return null

        val devices = discovery.getOrNull() ?: return null
        val configured = DeviceSecrets.switchBotMacAddress

        return if (configured != null) {
            devices.find { it.address.equals(configured, ignoreCase = true) }
        } else {
            devices.find { it.name?.contains("SwitchBot", ignoreCase = true) == true }
        }
    }
}

private object SwitchBotOpenerProtocol {

    val openCommand = byteArrayOf(
        0x57.toByte(), 0x01.toByte(), 0x01.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte()
    )

    val closeCommand = byteArrayOf(
        0x57.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte()
    )

    val statusCommand = byteArrayOf(
        0x57.toByte(), 0x02.toByte(), 0x00.toByte(),
        0x01.toByte(), 0x00.toByte(), 0xF6.toByte()
    )
}
