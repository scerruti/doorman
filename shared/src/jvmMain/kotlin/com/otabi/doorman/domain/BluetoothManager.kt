package com.otabi.doorman.domain

actual class BluetoothManager {
    actual suspend fun scanForDevices(): List<BleDeviceAdvertisement> {
        // TODO: implement JVM/Python-backed BLE scanning
        return emptyList()
    }

    actual suspend fun connect(mac: String): BluetoothConnection {
        // TODO: implement JVM/Python-backed BLE connection
        throw NotImplementedError("JVM BluetoothManager.connect not implemented yet")
    }
}
