package com.otabi.doorman.platform

import java.util.Locale

/** Format MAC from manufacturer payload bytes (companyId default 0x0969) */
fun macFromScanRecordLike(scan: ScanRecordLike?, companyId: Int = 0x0969, macOffsetInManuf: Int = 0): String? {
    val manuf = scan?.getManufacturerSpecificData(companyId) ?: return null
    if (manuf.size < macOffsetInManuf + 6) return null
    val macBytes = manuf.copyOfRange(macOffsetInManuf, macOffsetInManuf + 6)
    return macBytes.joinToString(":") { String.format(Locale.US, "%02X", it) }
}
