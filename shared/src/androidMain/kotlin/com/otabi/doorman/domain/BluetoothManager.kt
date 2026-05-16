package com.otabi.doorman.domain

actual class BluetoothManager {
    actual suspend fun scanForDevices(): List<BleDeviceAdvertisement> {
        // TODO: implement Android BLE scanning
        return emptyList()
    }

    actual suspend fun connect(mac: String): BluetoothConnection {
        // TODO: implement Android BLE connection
        throw NotImplementedError("Android BluetoothManager.connect not implemented yet")
    }
}
