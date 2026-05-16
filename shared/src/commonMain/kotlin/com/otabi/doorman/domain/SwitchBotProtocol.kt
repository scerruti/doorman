package com.otabi.doorman.domain

class SwitchBotProtocol(
    val keyHex: String = "00000000000000000000000000000000"
) {
    fun parseNotification(data: ByteArray): DoorStatus {
        if (data.size < 6) return DoorStatus.UNKNOWN
        return if (data[4].toInt() and 0x01 == 0) DoorStatus.OPEN else DoorStatus.CLOSED
    }
}
