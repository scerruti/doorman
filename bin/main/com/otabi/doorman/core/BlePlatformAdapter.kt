package com.otabi.doorman.core

import com.otabi.doorman.platform.Discovery

interface BlePlatformAdapter {
    /** The expected local name of the device on this platform */
    val expectedDeviceName: String?
    
    /** * Extracts a MAC address from the platform-specific payload.
     * On macOS/Simulator: extracts from the Bleak advHex string.
     * On Android: extracts from the native ScanRecord.
     */
    fun extractMac(discovery: Discovery): String?
}