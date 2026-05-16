package com.otabi.doorman.domain

import kotlinx.coroutines.flow.Flow

interface BluetoothManager {
    fun scanForService(serviceUuid: String): Flow<BleDeviceAdvertisement>
    fun stopScan()
    suspend fun connect(macAddress: String): Result<BluetoothConnection>
}
