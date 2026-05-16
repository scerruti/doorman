package com.otabi.doorman.domain

/**
 * Handles SwitchBot's Application-Layer security and payload generation.
 * 
 * @param keyHex The 32-character (16-byte) hex string representing the device password.
 * @param keyId The identification byte for the key. Default is 0x51.
 */
expect class SwitchBotProtocol(
    keyHex: String,
    keyId: Byte = 0x51.toByte()
) {
    /**
     * Builds the 17-byte encrypted relay command.
     */
    fun buildToggleCommand(): ByteArray

    /**
     * Parses the Manufacturer Data from a Type 0x03 Scan Advertisement.
     */
    fun parseStatus(mfrData: ByteArray): DoorStatus
}