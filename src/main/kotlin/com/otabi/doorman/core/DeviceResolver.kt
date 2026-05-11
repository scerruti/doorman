package com.otabi.doorman.core

import com.otabi.doorman.platform.RssiRangeGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object DeviceResolver {
    const val SWITCHBOT_COMPANY_ID = 0x0969
    const val EXPECTED_NAME = "WoSwitchGDO"

    /** * Pure business logic: Does this advertisement match our garage door? 
     */
    fun isTarget(adv: ParsedAdvertisement, targetMac: String): Boolean {
        // 1. Check Name
        if (adv.name == EXPECTED_NAME) {
            return true
        }

        // 2. Check MAC inside Manufacturer Data
        val switchbotBytes = adv.manufacturerData[SWITCHBOT_COMPANY_ID]
        if (switchbotBytes != null && switchbotBytes.size >= 6) {
            val extractedMac = switchbotBytes.copyOfRange(0, 6)
                .joinToString(":") { String.format("%02X", it).uppercase() }
            
            if (extractedMac.equals(targetMac, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    /** * Suspend-friendly wait for gate stability. 
     * Required for Option 2 in the testing menu.
     */
    suspend fun waitForStableInRangeSuspend(
        gate: RssiRangeGate,
        deviceAddr: String,
        thresholdRssi: Int,
        timeoutMs: Long,
        pollIntervalMs: Long = 150L
    ): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (!gate.isStableInRange(deviceAddr, thresholdRssi)) {
                delay(pollIntervalMs)
            }
            true
        } ?: false
    }
}