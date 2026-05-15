package com.otabi.doorman.platform

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.otabi.doorman.domain.BleDeviceAdvertisement
import com.otabi.doorman.domain.BluetoothConnection
import com.otabi.doorman.domain.BluetoothManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Native Android implementation of the BluetoothManager.
 * NOTE: The consuming Android Application must request and grant BLUETOOTH_SCAN and BLUETOOTH_CONNECT permissions.
 */
@SuppressLint("MissingPermission")
class AndroidBluetoothManager(
    private val context: Context
) : BluetoothManager {

    private val adapter: BluetoothAdapter? by lazy {
        context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
    }

    override fun scanForService(serviceUuid: String): Flow<BleDeviceAdvertisement> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(Exception("Bluetooth is not enabled or available on this device."))
            return@callbackFlow
        }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // SwitchBot Manufacturer ID is 0x0969
                val mfrData = result.scanRecord?.getManufacturerSpecificData(0x0969)
                
                val mac = result.device.address
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "Unknown Device"
                val rssi = result.rssi
                
                // Only emit if we actually found manufacturer data (which contains our Open/Closed state)
                if (mfrData != null) {
                    trySend(BleDeviceAdvertisement(mac, name, mfrData, rssi))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(Exception("Android BLE Scan failed with error code: $errorCode"))
            }
        }

        // We look for the SwitchBot Service UUID, just like the Python Daemon
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(serviceUuid)))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)

        awaitClose {
            scanner.stopScan(scanCallback)
        }
    }

    override fun stopScan() {
        // Handled automatically when the Flow is cancelled/collected
    }

    override suspend fun connect(macAddress: String): Result<BluetoothConnection> {
        val device = adapter?.getRemoteDevice(macAddress)
            ?: return Result.failure(Exception("Device not found or Bluetooth disabled"))

        return try {
            val connection = AndroidBluetoothConnection(context, device)
            connection.connectGatt()
            Result.success(connection)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@SuppressLint("MissingPermission")
class AndroidBluetoothConnection(
    private val context: Context,
    private val device: BluetoothDevice
) : BluetoothConnection {

    private var gatt: BluetoothGatt? = null
    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var connectionContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionContinuation?.takeIf { it.isActive }?.resumeWithException(Exception("GATT Error status: $status"))
                g.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices() // Must discover services before we can write to them
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionContinuation?.takeIf { it.isActive }?.resumeWithException(Exception("Disconnected"))
                gatt?.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connectionContinuation?.takeIf { it.isActive }?.resume(Unit)
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            _notifications.tryEmit(characteristic.value)
        }
    }

    suspend fun connectGatt() = suspendCancellableCoroutine<Unit> { cont ->
        connectionContinuation = cont
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        cont.invokeOnCancellation { disconnect() }
    }

    @Suppress("DEPRECATION")
    override suspend fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, payload: ByteArray): Result<Unit> {
        val char = gatt?.getService(UUID.fromString(serviceUuid))?.getCharacteristic(UUID.fromString(characteristicUuid))
            ?: return Result.failure(Exception("Characteristic not found"))

        char.value = payload
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt?.writeCharacteristic(char)
        return Result.success(Unit) // Modern Android APIs handle write callbacks, but fire-and-forget is fine for this hardware
    }

    override suspend fun readCharacteristic(serviceUuid: String, characteristicUuid: String): Result<ByteArray> {
        return Result.failure(UnsupportedOperationException("Read not implemented"))
    }

    override fun subscribeToNotifications(serviceUuid: String, characteristicUuid: String): Flow<ByteArray> {
        return _notifications.asSharedFlow()
    }

    override fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
    }
}