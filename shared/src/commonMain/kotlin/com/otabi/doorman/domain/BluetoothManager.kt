package com.otabi.doorman.domain

import kotlinx.coroutines.flow.Flow

/**
 * A BLE advertisement emitted during scanning.
 *
 * For SwitchBot Relay-based devices:
 *  - macAddress is the *real* MAC (your daemon extracts this)
 *  - manufacturerData may contain coarse door state (optional)
 *  - rssi is useful for proximity heuristics
 */
data class BleDeviceAdvertisement(
    val macAddress: String,
    val name: String?,
    val manufacturerData: ByteArray,
    val rssi: Int = 0
)

/**
 * Represents an active BLE connection to a device.
 *
 * For SwitchBot Relay-based devices:
 *  - RX characteristic is write-only
 *  - TX characteristic is notify-only
 *  - readCharacteristic() is not used and may return failure
 */
interface BluetoothConnection {

    /**
     * Writes raw bytes to a BLE characteristic.
     * Used for sending UART frames (e.g., toggle command).
     */
    suspend fun writeCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
        payload: ByteArray
    ): Result<Unit>

    /**
     * Reading is not used for SwitchBot Relay devices.
     * Implementations may return failure or empty data.
     */
    suspend fun readCharacteristic(
        serviceUuid: String,
        characteristicUuid: String
    ): Result<ByteArray>

    /**
     * Subscribes to notifications from a BLE characteristic.
     * For SwitchBot Relay devices, this is where door sensor
     * state is received as raw UART frames.
     */
    fun subscribeToNotifications(
        serviceUuid: String,
        characteristicUuid: String
    ): Flow<ByteArray>

    /**
     * Disconnects the BLE connection.
     */
    fun disconnect()
}

/**
 * Platform-agnostic BLE manager.
 *
 * For SwitchBot Relay-based devices:
 *  - scanForService() must emit devices even if they do NOT
 *    advertise the full 128-bit service UUID.
 *  - Implementations should also detect manufacturer ID 0x0969.
 */
interface BluetoothManager {

    /**
     * Emits BLE advertisements for devices matching the service UUID
     * OR manufacturer ID (SwitchBot uses 0x0969).
     */
    fun scanForService(serviceUuid: String): Flow<BleDeviceAdvertisement>

    /**
     * Stops scanning.
     */
    fun stopScan()

    /**
     * Connects to a device by MAC address.
     * Returns a BluetoothConnection on success.
     */
    suspend fun connect(macAddress: String): Result<BluetoothConnection>
}
