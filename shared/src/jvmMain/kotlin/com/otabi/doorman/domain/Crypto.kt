package com.otabi.doorman.domain

actual object Crypto {
    actual fun aesEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        throw NotImplementedError("JVM Crypto.aesEncrypt not implemented yet")
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        throw NotImplementedError("JVM Crypto.hmacSha256 not implemented yet")
    }
}
