package com.otabi.doorman.platform

import com.otabi.doorman.domain.BluetoothManager
import com.otabi.doorman.domain.DoorController
import com.otabi.doorman.domain.DoorStatus
import kotlinx.coroutines.delay
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

class SwitchBotDoorController(private val bluetoothManager: BluetoothManager) : DoorController {

    private var lastCommandTime = 0L

    override suspend fun openDoor(): Result<Unit> {
        return sendToggleCommand("open")
    }

    override suspend fun closeDoor(): Result<Unit> {
        return sendToggleCommand("close")
    }

    override suspend fun getStatus(): Result<DoorStatus> {
        // SwitchBot Bot is stateless toggle, no status available
        // Could estimate based on last command time, but for now return UNKNOWN
        return Result.success(DoorStatus.UNKNOWN)
    }

    private suspend fun sendToggleCommand(action: String): Result<Unit> {
        println("Attempting to $action door via SwitchBot...")

        // Discover devices
        val discoveryResult = bluetoothManager.discoverDevices()
        if (discoveryResult.isFailure) {
            return Result.failure(discoveryResult.exceptionOrNull() ?: Exception("Discovery failed"))
        }

        val devices = discoveryResult.getOrNull() ?: emptyList()
        println("Discovered ${devices.size} BLE device(s):")
        devices.forEach { device ->
            println("  - ${device.name ?: "Unknown"} (${device.address})")
        }

        val configuredAddress = DeviceSecrets.switchBotMacAddress
        val switchBotDevice = configuredAddress?.let { expectedAddress ->
            devices.find { it.address.equals(expectedAddress, ignoreCase = true) }
        } ?: devices.find { it.name?.contains("SwitchBot", ignoreCase = true) == true }

        if (switchBotDevice == null) {
            return if (configuredAddress != null) {
                Result.failure(Exception("Configured SwitchBot MAC address is not currently visible in BLE scan"))
            } else {
                Result.failure(Exception("No SwitchBot device found"))
            }
        }

        // Connect
        val connectionResult = bluetoothManager.connect(switchBotDevice)
        if (connectionResult.isFailure) {
            return Result.failure(connectionResult.exceptionOrNull() ?: Exception("Connection failed"))
        }

        val connection = connectionResult.getOrNull()!!
        try {
            // SwitchBot Bot toggle command: 0x57 0x01
            val command = byteArrayOf(0x57, 0x01)
            val sendResult = connection.sendCommand(command)
            if (sendResult.isFailure) {
                return Result.failure(sendResult.exceptionOrNull() ?: Exception("Send failed"))
            }

            lastCommandTime = System.currentTimeMillis()
            println("Door $action command sent successfully")
            return Result.success(Unit)
        } finally {
            connection.disconnect()
        }
    }
}