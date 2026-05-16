package com.otabi.doorman.domain

import kotlinx.coroutines.flow.StateFlow

interface DoorController {
    val state: StateFlow<DoorStatus>
    val isConnected: Boolean
    val discoveredDevices: List<BleDeviceAdvertisement>

    suspend fun connect(): Result<Unit>
    suspend fun getStatus(address: String? = null): Result<DoorStatus>
    suspend fun openDoor(address: String? = null): Result<Unit>
    suspend fun closeDoor(address: String? = null): Result<Unit>
    fun disconnect()
}
