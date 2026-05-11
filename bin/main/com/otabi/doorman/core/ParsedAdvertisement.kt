package com.otabi.doorman.core

/**
 * A platform-agnostic representation of BLE advertisement data.
 * Both Android and the Python socket daemon must map their data to this 
 * format before passing it to the core business logic.
 */
data class ParsedAdvertisement(
    val address: String,
    val name: String?,
    val manufacturerData: Map<Int, ByteArray> // Key: Company ID, Value: Payload
)