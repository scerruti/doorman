package com.otabi.doorman.domain

actual object Crypto {
    actual fun aesEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        throw NotImplementedError("Android Crypto.aesEncrypt not implemented yet")
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        throw NotImplementedError("Android Crypto.hmacSha256 not implemented yet")
    }
}
