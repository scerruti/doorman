package com.otabi.doorman.domain

import kotlinx.coroutines.flow.StateFlow

interface DoorController {
    val state: StateFlow<DoorStatus>
    val isConnected: Boolean

    suspend fun getStatus(address: String? = null): Result<DoorStatus>
    suspend fun openDoor(address: String? = null): Result<Unit>
    suspend fun closeDoor(address: String? = null): Result<Unit>
}