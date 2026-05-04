package com.otabi.doorman

import com.otabi.doorman.domain.DoorStatus
import com.otabi.doorman.platform.MacBluetoothManager
import com.otabi.doorman.platform.SwitchBotDoorController
import kotlinx.coroutines.*

suspend fun main() {
    val bluetoothManager = MacBluetoothManager()
    val controller = SwitchBotDoorController(bluetoothManager)

    println("=== Doorman Garage Door Controller (SwitchBot BLE) ===")
    println("Testing garage door operations...\n")

    // Test initial status
    println("1. Checking initial status:")
    val statusResult = controller.getStatus()
    if (statusResult.isSuccess) {
        println("Status: ${statusResult.getOrNull()}")
    } else {
        println("Error getting status: ${statusResult.exceptionOrNull()?.message}")
    }
    println()

    // Test opening door
    println("2. Opening door:")
    val openResult = controller.openDoor()
    if (openResult.isSuccess) {
        println("Door opened successfully")
    } else {
        println("Failed to open door: ${openResult.exceptionOrNull()?.message}")
    }
    println()

    // Check status after opening
    println("3. Status after opening:")
    val statusAfterOpen = controller.getStatus()
    if (statusAfterOpen.isSuccess) {
        println("Status: ${statusAfterOpen.getOrNull()}")
    } else {
        println("Error getting status: ${statusAfterOpen.exceptionOrNull()?.message}")
    }
    println()

    // Test closing door
    println("4. Closing door:")
    val closeResult = controller.closeDoor()
    if (closeResult.isSuccess) {
        println("Door closed successfully")
    } else {
        println("Failed to close door: ${closeResult.exceptionOrNull()?.message}")
    }
    println()

    // Final status
    println("5. Final status:")
    val finalStatus = controller.getStatus()
    if (finalStatus.isSuccess) {
        println("Status: ${finalStatus.getOrNull()}")
    } else {
        println("Error getting status: ${finalStatus.exceptionOrNull()?.message}")
    }
    println()
}