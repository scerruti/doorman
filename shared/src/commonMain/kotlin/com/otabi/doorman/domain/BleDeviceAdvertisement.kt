package com.otabi.doorman.domain

data class BleDeviceAdvertisement(
    val macAddress: String,
    val name: String?,
    val manufacturerData: ByteArray,
    val rssi: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleDeviceAdvertisement) return false
        return macAddress == other.macAddress &&
               name == other.name &&
               manufacturerData.contentEquals(other.manufacturerData) &&
               rssi == other.rssi
    }

    override fun hashCode(): Int {
        var result = macAddress.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + manufacturerData.contentHashCode()
        result = 31 * result + rssi
        return result
    }
}
