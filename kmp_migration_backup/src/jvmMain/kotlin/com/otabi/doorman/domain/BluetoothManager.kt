package com.otabi.doorman.domain

actual class BluetoothManager {
    actual suspend fun scanForDevices(): List<BleDeviceAdvertisement> {
        // TODO: implement macOS scanning
        return emptyList()
    }

    actual suspend fun connect(mac: String): BluetoothConnection {
        // TODO: implement macOS BLE connection
        throw NotImplementedError("macOS BluetoothManager.connect not implemented yet")
    }
}
