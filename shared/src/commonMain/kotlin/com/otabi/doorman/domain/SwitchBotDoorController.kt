package com.otabi.doorman.domain

class SwitchBotDoorController(
    private val bluetoothManager: BluetoothManager,
    private val protocol: SwitchBotProtocol
) : DoorController {

    override suspend fun openDoor(address: String?): Result<Unit> =
        sendToggle(address)

    override suspend fun closeDoor(address: String?): Result<Unit> =
        sendToggle(address)

    override suspend fun getStatus(address: String?): Result<DoorStatus> {
        val mac = address ?: return Result.failure(IllegalArgumentException("MAC address is required"))

        val devices = try {
            bluetoothManager.scanForDevices()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val target = devices.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
            ?: return Result.failure(IllegalStateException("Device with MAC $mac not found in scan"))

        val mfr = target.manufacturerData
            ?: return Result.success(DoorStatus.UNKNOWN)

        return try {
            Result.success(protocol.parseStatus(mfr))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun sendToggle(address: String?): Result<Unit> {
        val mac = address ?: return Result.failure(IllegalArgumentException("MAC address is required"))

        val connection = try {
            bluetoothManager.connect(mac)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return try {
            val command = protocol.buildToggleCommand()
            connection.writeCharacteristic(SERVICE_UUID, CHARACTERISTIC_UUID, command)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
                // ignore disconnect errors
            }
        }
    }

    private companion object {
        // TODO: replace with the real GDO service/characteristic UUIDs
        const val SERVICE_UUID = "0000fd3d-0000-1000-8000-00805f9b34fb"
        const val CHARACTERISTIC_UUID = "0000fd3e-0000-1000-8000-00805f9b34fb"
    }
}
