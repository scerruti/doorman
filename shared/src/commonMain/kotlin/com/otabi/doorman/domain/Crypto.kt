package com.otabi.doorman.domain

expect object Crypto {
    fun aesEncrypt(key: ByteArray, data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
}
