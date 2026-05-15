package com.otabi.doorman.macos

import com.otabi.doorman.domain.*
import com.otabi.doorman.platform.*
import kotlinx.coroutines.*
import kotlin.system.exitProcess
import java.util.Properties
import java.io.File

suspend fun main(): Unit = coroutineScope {
    val gdoMac = loadProperty("switchbot.mac.address")
    val keyHex = loadProperty("switchbot.device.encryption.key").takeIf { it.isNotBlank() } ?: "00000000000000000000000000000000"
    
    val bluetoothManager = MacBluetoothManager()
    val protocol = SwitchBotProtocol(keyHex)

    // For faster UI testing you can drop the travelTimeMs to 5000L
    val controller = SwitchBotDoorController(bluetoothManager, gdoMac, protocol, this, travelTimeMs = 15000L)

    println("=== Doorman Garage Door Controller ===")
    println("Loaded configuration:")
    println("  Target MAC: ${if (gdoMac.isNotBlank()) gdoMac else "<Not Configured>"}")
    println("======================================\n")
    
    // UI Listener: Only interrupt the prompt if the physical state actually transitions
    launch {
        var previousState = DoorStatus.UNKNOWN
        controller.state.collect { currentState ->
            // Ignore the initial initialization from UNKNOWN to the first known state
            if (previousState != DoorStatus.UNKNOWN && currentState != previousState) {
                print("\r[State Transition] \uD83D\uDEAA Garage Door changed: $previousState -> $currentState\nSelect option: ")
            }
            previousState = currentState
        }
    }

    while (true) {
        println("\n=== Doorman Test Menu ===")
        println("1. Show discovered devices")
        println("2. Connect to Target ($gdoMac)")
        
        if (controller.isConnected) {
            println("3. Show Status")
            println("4. Open door")
            println("5. Close door")
        } else {
            println("\u001B[90m3. Show Status (Requires Connection)\u001B[0m")
            println("\u001B[90m4. Open door (Requires Connection)\u001B[0m")
            println("\u001B[90m5. Close door (Requires Connection)\u001B[0m")
        }
        println("6. Quit")
        print("Select option: ")

        val input = readlnOrNull()?.trim()
        if (input.isNullOrEmpty()) continue

        when (input) {
            "1" -> {
                println("Discovered devices:")
                val devices = controller.discoveredDevices
                if (devices.isEmpty()) {
                    println("No devices discovered yet. Waiting for BLE scan results...")
                } else {
                    devices.forEach { ad ->
                        val advHex = ad.manufacturerData.joinToString("") { "%02X".format(it) }
                        println("${ad.macAddress} name=${ad.name} rssi=${ad.rssi} adv=$advHex")
                    }
                }
                
                println("\n--- Target Device Status ---")
                if (gdoMac.isNotBlank()) {
                    val target = devices.find { it.macAddress.equals(gdoMac, ignoreCase = true) }
                    if (target != null) {
                        val advHex = target.manufacturerData.joinToString("") { "%02X".format(it) }
                        println("✅ FOUND: ${target.macAddress} (rssi=${target.rssi}, adv=$advHex)")
                    } else {
                        println("⏳ SEARCHING: $gdoMac (Not yet discovered in range)")
                    }
                } else {
                    println("❌ No target MAC address configured in device-secrets.properties.")
                }
            }
            "2" -> {
                if (gdoMac.isEmpty()) {
                    println("No MAC address configured in device-secrets.properties.")
                    continue
                }
                println("Connecting to $gdoMac...")
                val result = controller.connect()
                if (result.isSuccess) {
                    println("Connected successfully.")
                } else {
                    println("Failed to connect: ${result.exceptionOrNull()?.message}")
                }
            }
            "3" -> {
                if (!controller.isConnected) {
                    println("Please connect first (Option 2).")
                } else {
                    println("Current known door status: ${controller.state.value}")
                }
            }
            "4" -> {
                if (!controller.isConnected) {
                    println("Please connect first (Option 2).")
                } else {
                    println("Opening door...")
                    val result = controller.openDoor()
                    if (result.isSuccess) {
                        println("Command processed successfully.")
                    } else {
                        println("Failed to open door: ${result.exceptionOrNull()?.message}")
                    }
                }
            }
            "5" -> {
                if (!controller.isConnected) {
                    println("Please connect first (Option 2).")
                } else {
                    println("Closing door...")
                    val result = controller.closeDoor()
                    if (result.isSuccess) {
                        println("Command processed successfully.")
                    } else {
                        println("Failed to close door: ${result.exceptionOrNull()?.message}")
                    }
                }
            }
            "6", "q", "quit" -> {
                println("Exiting.")
                break
            }
            else -> println("Invalid choice.")
        }
    }

    controller.disconnect()
    exitProcess(0)
}

private fun loadProperty(key: String): String {
    return try {
        val props = Properties()
        var file = File("../device-secrets.properties")
        if (!file.exists()) {
            file = File("device-secrets.properties")
        }
        if (file.exists()) {
            props.load(file.inputStream())
            props.getProperty(key, "")
        } else ""
    } catch (e: Exception) { "" }
}