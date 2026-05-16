package com.otabi.doorman.domain

expect class SwitchBotProtocol(
    keyHex: String,
    keyId: Byte = 0x51
) {
    fun buildToggleCommand(): ByteArray
    fun parseStatus(mfrData: ByteArray): DoorStatus
    fun parseNotification(data: ByteArray): DoorStatus
}
