package com.otabi.doorman.domain

interface BluetoothConnection {
    suspend fun writeCharacteristic(service: String, characteristic: String, data: ByteArray)
    suspend fun scanForService(service: String, onNotify: (ByteArray) -> Unit)
    suspend fun disconnect()
}
