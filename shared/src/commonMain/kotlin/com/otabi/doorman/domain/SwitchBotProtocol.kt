package com.otabi.doorman.domain

class SwitchBotProtocol(
    private val keyHex: String,
    private val keyId: Byte = 0x51
) {
    fun buildToggleCommand(): ByteArray {
        // TODO: implement real command building
        return byteArrayOf()
    }

    fun parseStatus(mfrData: ByteArray): DoorStatus {
        // TODO: implement real status parsing
        return DoorStatus.Unknown
    }

    fun parseNotification(data: ByteArray): DoorStatus {
        // TODO: implement real notification parsing
        return parseStatus(data)
    }
}
