package com.otabi.doorman.domain

/**
 * SwitchBot protocol handler for classic, unencrypted UART devices
 * such as the Relay-based garage door add-on.
 *
 * @param keyHex Kept for API compatibility; unused for unencrypted devices.
 * @param keyId  Kept for API compatibility; unused for unencrypted devices.
 */
expect class SwitchBotProtocol(
    keyHex: String,
    keyId: Byte = 0x51.toByte()
) {

    /**
     * Builds a raw UART toggle command:
     *   0x57 <len> 0x01 0x01 <checksum>
     */
    fun buildToggleCommand(): ByteArray

    /**
     * Parses manufacturer data from BLE advertisements.
     * Optional for this device; used only for coarse state.
     */
    fun parseStatus(mfrData: ByteArray): DoorStatus

    /**
     * Parses TX characteristic notifications from the real device.
     * This is the authoritative door state (OPEN / CLOSED).
     */
    fun parseNotification(data: ByteArray): DoorStatus
}
