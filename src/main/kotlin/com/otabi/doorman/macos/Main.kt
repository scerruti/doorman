package com.otabi.doorman.macos

import com.otabi.doorman.domain.BluetoothManager
import com.otabi.doorman.domain.SwitchBotDoorController
import com.otabi.doorman.domain.SwitchBotProtocol
import com.otabi.doorman.domain.DoorStatus
import kotlinx.coroutines.*

suspend fun main(): Unit = coroutineScope {
    println("=== Doorman (Shared BLE Edition) ===")

    val gdoMac = loadGdoMac()
    if (gdoMac.isBlank()) {
        println("Error: No MAC configured in device-secrets.properties")
        return@coroutineScope
    }

    val bluetoothManager = BluetoothManager()   // expect/actual
    val protocol = SwitchBotProtocol(
        keyHex = "00000000000000000000000000000000", // replace with real key
        keyId = 0x51
    )
    val controller = SwitchBotDoorController(bluetoothManager, protocol)

    var resolvedAddr: String? = null

    while (true) {
        println("\n=== Menu ===")
        println("1. Scan for devices")
        println("2. Resolve GDO by MAC")
        println("3. Get status")
        println("4. Toggle door (open/close)")
        println("5. Quit")
        print("Select option: ")

        when (readlnOrNull()?.trim()) {
            "1" -> {
                println("Scanning...")
                try {
                    val devices = bluetoothManager.scanForDevices()
                    devices.forEach {
                        println("${it.mac}  name=${it.name}  rssi=${it.rssi}  mfr=${it.manufacturerData?.size ?: 0} bytes")
                    }
                } catch (e: Exception) {
                    println("Scan failed: ${e.message}")
                }
            }

            "2" -> {
                println("Scanning for $gdoMac...")
                val devices = try {
                    bluetoothManager.scanForDevices()
                } catch (e: Exception) {
                    println("Scan failed: ${e.message}")
                    continue
                }

                resolvedAddr = devices.firstOrNull { it.mac.equals(gdoMac, ignoreCase = true) }?.mac

                if (resolvedAddr == null) {
                    println("GDO not found in scan results.")
                } else {
                    println("Resolved GDO at: $resolvedAddr")
                }
            }

            "3" -> {
                if (resolvedAddr == null) {
                    println("Resolve the GDO first (Option 2).")
                    continue
                }

                println("Checking status...")
                val result = controller.getStatus(resolvedAddr)
                result.onSuccess { status ->
                    println("Status: $status")
                }.onFailure { e ->
                    println("Error: ${e.message}")
                }
            }

            "4" -> {
                if (resolvedAddr == null) {
                    println("Resolve the GDO first (Option 2).")
                    continue
                }

                println("Sending toggle command...")
                val result = controller.openDoor(resolvedAddr)
                result.onSuccess {
                    println("Toggle sent.")
                }.onFailure { e ->
                    println("Error: ${e.message}")
                }
            }

            "5", "q", "quit" -> {
                println("Exiting.")
                return@coroutineScope
            }

            else -> println("Invalid choice.")
        }
    }
}

private fun loadGdoMac(): String {
    return try {
        val props = java.util.Properties()
        val file = java.io.File("device-secrets.properties")
        props.load(file.inputStream())
        props.getProperty("switchbot.mac.address", "")
    } catch (e: Exception) { "" }
}
