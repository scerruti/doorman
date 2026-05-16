package com.otabi.doorman.domain

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesCtr(private val key: ByteArray, private val keyId: Byte) {

    companion object {
        fun fromHex(keyHex: String, keyId: Byte): AesCtr =
            AesCtr(keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), keyId)
    }


    private fun makeCipher(ivFirstByte: Byte, mode: Int): Cipher {
        // CTR IV: first byte + 15 zero bytes (matches Python iv construction)
        val iv = ByteArray(16).also { it[0] = ivFirstByte }
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(mode, keySpec, ivSpec)
        }
    }

    fun encrypt(data: ByteArray): ByteArray {
        val cipher = makeCipher(keyId, Cipher.ENCRYPT_MODE)
        val ciphertext = cipher.doFinal(data)
        // Prepend KEY_ID byte, matching Python: return KEY_ID + cipher...update(data)
        return byteArrayOf(keyId) + ciphertext
    }

    fun decrypt(data: ByteArray): ByteArray {
        if (data.size < 2) return data
        val ivByte = data[0]
        val cipher = makeCipher(ivByte, Cipher.DECRYPT_MODE)
        return cipher.doFinal(data, 1, data.size - 1)   // skip the KEY_ID prefix byte
    }
}