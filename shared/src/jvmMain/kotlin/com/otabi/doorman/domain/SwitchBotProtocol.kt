package com.otabi.doorman.domain

/**
 * Handles SwitchBot's application-layer payload generation for the
 * classic, unencrypted UART protocol used by Relay-style devices.
 *
 * @param keyHex Kept for API compatibility; not used for unencrypted devices.
 * @param keyId  Kept for API compatibility; not used for unencrypted devices.
 */
actual class SwitchBotProtocol actual constructor(
    keyHex: String,
    private val keyId: Byte
) {

    /**
     * Builds a raw SwitchBot UART "toggle" command for the relay.
     *
     * Frame format:
     *   0x57 <len> <cmd> <payload...> <checksum>
     *
     * Relay toggle is:
     *   cmd = 0x01
     *   payload = 0x01
     */
    actual fun buildToggleCommand(): ByteArray {
        return buildCommand(
            cmd = 0x01.toByte(),
            payload = byteArrayOf(0x01)
        )
    }

    /**
     * Generic helper to build a SwitchBot UART frame.
     */
    private fun buildCommand(cmd: Byte, payload: ByteArray): ByteArray {
        val len = (1 + payload.size).toByte() // cmd + payload
        val frameSize = 1 + 1 + 1 + payload.size + 1
        val frame = ByteArray(frameSize)

        var idx = 0
        frame[idx++] = 0x57.toByte()
        frame[idx++] = len
        frame[idx++] = cmd
        payload.forEach { b -> frame[idx++] = b }

        var checksum = 0
        for (i in 0 until frameSize - 1) {
            checksum = checksum xor (frame[i].toInt() and 0xFF)
        }
        frame[frameSize - 1] = checksum.toByte()

        return frame
    }

    /**
     * Parses Manufacturer Data from BLE advertisements.
     *
     * Only used for coarse state; real device state comes from notifications.
     */
    actual fun parseStatus(mfrData: ByteArray): DoorStatus {
        if (mfrData.size <= 6) return DoorStatus.UNKNOWN

        val statusByte = mfrData[6].toInt() and 0xFF
        return if ((statusByte and 0x01) == 0) DoorStatus.OPEN else DoorStatus.CLOSED
    }

    /**
     * Parses TX characteristic notifications from the real device.
     *
     * Real Relay-based garage add-on sends:
     *   0x57 <len> <cmd> <payload...> <checksum>
     *
     * Door sensor state is typically in payload[0]:
     *   0x00 = CLOSED
     *   0x01 = OPEN
     *
     * If your sniff shows a different layout, we can adjust this.
     */
    fun parseNotification(data: ByteArray): DoorStatus {
        if (data.isEmpty() || data[0] != 0x57.toByte()) {
            return DoorStatus.UNKNOWN
        }

        // Example: 57 02 03 <state> <checksum>
        if (data.size >= 4) {
            val state = data[3].toInt() and 0xFF
            return when (state) {
                0x00 -> DoorStatus.CLOSED
                0x01 -> DoorStatus.OPEN
                else -> DoorStatus.UNKNOWN
            }
        }

        return DoorStatus.UNKNOWN
    }
}
