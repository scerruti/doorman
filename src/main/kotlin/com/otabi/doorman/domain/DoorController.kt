package com.otabi.doorman.domain

interface DoorController {
    suspend fun openDoor(): Result<Unit>
    suspend fun closeDoor(): Result<Unit>
    suspend fun getStatus(): Result<DoorStatus>
}