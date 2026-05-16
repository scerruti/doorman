package com.otabi.doorman.domain

expect class BluetoothManager {
    suspend fun scanForDevices(): List<BleDeviceAdvertisement>
    suspend fun connect(mac: String): BluetoothConnection
}
