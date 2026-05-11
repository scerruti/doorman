package com.otabi.doorman.platform

import com.otabi.doorman.domain.BluetoothManager
import com.otabi.doorman.domain.BluetoothDevice
import com.otabi.doorman.domain.DoorController
import com.otabi.doorman.domain.DoorStatus
import java.io.File
import java.util.Properties

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
            
            // We call sendCommand, but since it returns Result<Unit>, 
            // we just check if the request was sent successfully.
            connection.sendCommand(SwitchBotOpenerProtocol.statusCommand).getOrThrow()
            
            connection.disconnect()
            
            // Since we can't read the response bytes, we return UNKNOWN
            Result.success(DoorStatus.UNKNOWN)
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
            // This now perfectly matches Result<Unit> returning from the manager
            connection.sendCommand(command)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection.disconnect()
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
    val openCommand = byteArrayOf(0x57, 0x01, 0x01, 0x00, 0x00, 0x00)
    val closeCommand = byteArrayOf(0x57, 0x01, 0x00, 0x00, 0x00, 0x00)
    val statusCommand = byteArrayOf(0x57, 0x02, 0x00)
}