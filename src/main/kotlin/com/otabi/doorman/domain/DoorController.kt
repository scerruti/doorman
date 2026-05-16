package com.otabi.doorman.domain

interface DoorController {
    suspend fun getStatus(address: String? = null): Result<DoorStatus>
    suspend fun openDoor(address: String? = null): Result<Unit>
    suspend fun closeDoor(address: String? = null): Result<Unit>
}