package com.otabi.doorman.domain

import kotlinx.coroutines.flow.Flow

interface BluetoothConnection {
    suspend fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, payload: ByteArray): Result<Unit>
    suspend fun readCharacteristic(serviceUuid: String, characteristicUuid: String): Result<ByteArray>
    fun subscribeToNotifications(serviceUuid: String, characteristicUuid: String): Flow<ByteArray>
    fun disconnect()
}
