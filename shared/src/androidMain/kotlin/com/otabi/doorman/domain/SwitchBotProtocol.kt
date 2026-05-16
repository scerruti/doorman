package com.otabi.doorman.domain

import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles SwitchBot's Application-Layer security and payload generation.
 * 
 * @param keyHex The 32-character (16-byte) hex string representing the device password.
 * @param keyId The identification byte for the key. Default is 0x51.
 */
actual class SwitchBotProtocol actual constructor(
    keyHex: String,
    private val keyId: Byte
) {
    private val keyBytes: ByteArray = keyHex.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private val secretKey = SecretKeySpec(keyBytes, "AES")
    private val counter = AtomicInteger(0)

    /**
     * Builds the 17-byte encrypted relay command.
     * By default, this creates the 0x57 01 01 (Toggle/Turn On) command.
     */
    actual fun buildToggleCommand(): ByteArray {
        val currentCounter = (counter.getAndIncrement() % 256).toByte()

        // Plaintext payload is exactly 16 bytes: [Counter, cmd, p1, p2, 0,0,0...]
        val plaintext = ByteArray(16)
        plaintext[0] = currentCounter
        plaintext[1] = 0x57.toByte() // Command
        plaintext[2] = 0x01.toByte() // Param 1
        plaintext[3] = 0x01.toByte() // Param 2

        // IV is 16 bytes: [KeyID, 0, 0...]
        val ivBytes = ByteArray(16)
        ivBytes[0] = keyId
        val ivSpec = IvParameterSpec(ivBytes)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

        val encryptedPayload = cipher.doFinal(plaintext)

        // Final packet: [KeyID] + [16 bytes encrypted payload]
        val packet = ByteArray(17)
        packet[0] = keyId
        System.arraycopy(encryptedPayload, 0, packet, 1, 16)

        return packet
    }

    /**
     * Parses the Manufacturer Data from a Type 0x03 Scan Advertisement.
     */
    actual fun parseStatus(mfrData: ByteArray): DoorStatus {
        if (mfrData.size <= 6) return DoorStatus.UNKNOWN
        
        return if ((mfrData[6].toInt() and 0x04) != 0) DoorStatus.OPEN else DoorStatus.CLOSED
    }
}