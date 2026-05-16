package com.otabi.doorman.domain

data class BleDeviceAdvertisement(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val manufacturerData: ByteArray?
)
