package com.otabi.doorman.domain

data class BluetoothDevice(
    val address: String, 
    val name: String?,
    val rssi: Int = 0,       // Added for signal strength
    val advHex: String = ""  // Added for manufacturer data/service info
)

interface BluetoothConnection {
    suspend fun sendCommand(command: ByteArray): Result<Unit>
    suspend fun readData(): Result<ByteArray>
    fun disconnect()
}

interface BluetoothManager {
    suspend fun discoverDevices(): Result<List<BluetoothDevice>>
    suspend fun connect(device: BluetoothDevice): Result<BluetoothConnection>
}