package com.otabi.doorman.platform

import com.otabi.doorman.domain.BluetoothConnection
import com.otabi.doorman.domain.BluetoothDevice
import com.otabi.doorman.domain.BluetoothManager
import kotlinx.coroutines.*
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Real BLE implementation using Python bleak library
// Requires Python 3 and bleak installed: pip install bleak
class MacBluetoothManager : BluetoothManager {

    private val pythonScript = File("src/main/resources/switchbot_ble.py").absolutePath

    override suspend fun discoverDevices(): Result<List<BluetoothDevice>> {
        return withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("python3", pythonScript, "discover")
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    return@withContext Result.failure(Exception("Discovery failed: $output"))
                }

                val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
                val element = json.parseToJsonElement(output.trim())
                val bluetoothDevices = element.jsonArray.mapNotNull { item ->
                    val obj = item.jsonObject
                    val address = obj["address"]?.jsonPrimitive?.content
                    val name = obj["name"]?.jsonPrimitive?.content
                    if (address == null) return@mapNotNull null
                    BluetoothDevice(address, name)
                }

                Result.success(bluetoothDevices)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun connect(device: BluetoothDevice): Result<BluetoothConnection> {
        // For SwitchBot, we don't maintain a persistent connection
        // Each command is a separate BLE operation
        return Result.success(SwitchBotConnection(device, pythonScript))
    }
}

class SwitchBotConnection(private val device: BluetoothDevice, private val pythonScript: String) : BluetoothConnection {

    override suspend fun sendCommand(command: ByteArray): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val commandHex = command.joinToString("") { "%02x".format(it) }
                val process = ProcessBuilder("python3", pythonScript, "send", device.address, commandHex)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    return@withContext Result.failure(Exception("Send failed: $output"))
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun readData(): Result<ByteArray> {
        // SwitchBot Bot doesn't provide status read, return empty
        return Result.success(byteArrayOf())
    }

    override fun disconnect() {
        // No persistent connection to disconnect
    }
}