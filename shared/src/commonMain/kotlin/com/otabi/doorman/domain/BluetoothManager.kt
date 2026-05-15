package com.otabi.doorman.domain

import kotlinx.coroutines.flow.Flow

data class BleDeviceAdvertisement(
    val macAddress: String,
    val name: String?,
    val manufacturerData: ByteArray,
    val rssi: Int = 0
)

/**
 * Represents an active Bluetooth connection to a specific device.
 */
interface BluetoothConnection {
    suspend fun writeCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
        payload: ByteArray
    ): Result<Unit>

    suspend fun readCharacteristic(
        serviceUuid: String,
        characteristicUuid: String
    ): Result<ByteArray>

    fun subscribeToNotifications(
        serviceUuid: String,
        characteristicUuid: String
    ): Flow<ByteArray>

    fun disconnect()
}

interface BluetoothManager {
    fun scanForService(serviceUuid: String): Flow<BleDeviceAdvertisement>
    fun stopScan()
    suspend fun connect(macAddress: String): Result<BluetoothConnection>
}