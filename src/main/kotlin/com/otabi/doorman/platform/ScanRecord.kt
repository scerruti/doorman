package com.otabi.doorman.platform

import java.util.UUID
import kotlin.experimental.and
import java.nio.ByteBuffer


/** Minimal ScanRecord-like container for JVM clients. */
data class ScanRecordLike(
    val manufacturerData: Map<Int, ByteArray>,
    val serviceUuids: List<String>,
    val serviceData: Map<String, ByteArray>,
    val localName: String?,
    val rawBytes: ByteArray
) {
    fun getManufacturerSpecificData(companyId: Int): ByteArray? = manufacturerData[companyId]
    fun getServiceData(uuid: String): ByteArray? = serviceData[uuid.lowercase()]
}

/** Hex -> bytes helper */
fun hexToBytes(hex: String): ByteArray? {
    val clean = hex.filter { it.isLetterOrDigit() }
    if (clean.length % 2 != 0) return null
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

/** Parse BLE advertisement LTV bytes into a ScanRecordLike */
fun parseScanRecordFromAdvHex(advHex: String): ScanRecordLike? {
    val bytes = hexToBytes(advHex) ?: return null
    val manuf = mutableMapOf<Int, ByteArray>()
    val svcUuids = mutableListOf<String>()
    val svcData = mutableMapOf<String, ByteArray>()
    var localName: String? = null

    var i = 0
    while (i < bytes.size) {
        val len = (bytes[i].toInt() and 0xFF)
        if (len == 0) break
        if (i + len >= bytes.size) break // malformed
        val type = (bytes[i + 1].toInt() and 0xFF)
        val valueStart = i + 2
        val valueLen = len - 1
        if (valueLen > 0 && valueStart + valueLen <= bytes.size) {
            val value = bytes.copyOfRange(valueStart, valueStart + valueLen)
            when (type) {
                0x02, 0x03 -> { // 16-bit UUIDs (incomplete/complete)
                    // each UUID is 2 bytes little-endian
                    var j = 0
                    while (j + 1 < value.size) {
                        val lo = value[j].toInt() and 0xFF
                        val hi = value[j + 1].toInt() and 0xFF
                        val uuid16 = String.format("%04x", (hi shl 8) or lo)
                        // expand to 128-bit base UUID
                        val full = "0000$uuid16-0000-1000-8000-00805f9b34fb"
                        svcUuids.add(full)
                        j += 2
                    }
                }
                0x06, 0x07 -> { // 128-bit UUIDs (incomplete/complete)
                    var j = 0
                    while (j + 15 < value.size) {
                        val uuidBytes = value.copyOfRange(j, j + 16)
                        // Convert little-endian 128-bit to canonical UUID string
                        val uuid = bytesToUuidString(uuidBytes)
                        svcUuids.add(uuid)
                        j += 16
                    }
                }
                0x08, 0x09 -> { // Short/Complete Local Name
                    localName = String(value, Charsets.UTF_8)
                }
                0x16 -> { // Service Data - 16-bit UUID + data
                    if (value.size >= 2) {
                        val lo = value[0].toInt() and 0xFF
                        val hi = value[1].toInt() and 0xFF
                        val uuid16 = String.format("%04x", (hi shl 8) or lo)
                        val full = "0000$uuid16-0000-1000-8000-00805f9b34fb"
                        val data = value.copyOfRange(2, value.size)
                        svcData[full.lowercase()] = data
                    }
                }
                0xFF -> { // Manufacturer Specific Data (company id little-endian)
                    if (value.size >= 2) {
                        val companyId = (value[1].toInt() and 0xFF shl 8) or (value[0].toInt() and 0xFF)
                        val data = value.copyOfRange(2, value.size)
                        manuf[companyId] = data
                    }
                }
                else -> {
                    // ignore other types for now
                }
            }
        }
        i += (1 + len)
    }

    return ScanRecordLike(manuf, svcUuids, svcData, localName, bytes)
}

/** Convert 16-byte little-endian UUID bytes to canonical UUID string */
fun bytesToUuidString(b: ByteArray): String {
    if (b.size != 16) return ""
    // CoreBluetooth/Android advertise 128-bit UUIDs in little-endian order for the first 3 fields
    // Convert to big-endian UUID string
    val bb = ByteArray(16)
    // time_low (4 bytes) reverse
    bb[0] = b[3]; bb[1] = b[2]; bb[2] = b[1]; bb[3] = b[0]
    // time_mid (2 bytes) reverse
    bb[4] = b[5]; bb[5] = b[4]
    // time_hi_and_version (2 bytes) reverse
    bb[6] = b[7]; bb[7] = b[6]
    // rest are same order
    for (i in 8..15) bb[i] = b[i]
    val msb = ByteBuffer.wrap(bb, 0, 8).long
    val lsb = ByteBuffer.wrap(bb, 8, 8).long
    return UUID(msb, lsb).toString()
}
