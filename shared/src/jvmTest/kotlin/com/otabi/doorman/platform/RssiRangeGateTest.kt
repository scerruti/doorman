package com.otabi.doorman.platform

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Test file for RssiRangeGate without test-only accessors or the hysteresis test.
 *
 * Place at: src/test/kotlin/com/otabi/doorman/platform/RssiRangeGateTest.kt
 */
class RssiRangeGateTest {

    @Test
    fun testAverageDecisionWithoutHysteresis() {
        val gate = RssiRangeGate(
            defaultWindowMs = 1000L,
            defaultMinSamples = 3,
            defaultMethod = RssiRangeGate.DecisionMethod.AVERAGE,
            hysteresisDb = 0
        )

        val addr = "AA:BB:CC:DD:EE:01"
        gate.clearDevice(addr)

        gate.recordRssi(addr, -76)
        gate.recordRssi(addr, -75)
        gate.recordRssi(addr, -74)

        // average = -75 -> threshold = -75 -> should be in range when hysteresis = 0
        val inRange = gate.isStableInRange(addr, thresholdRssi = -75, windowMs = 1000L, minSamples = 3)
        assertTrue(inRange, "Expected average (-75) to meet threshold -75 when hysteresis is 0")
    }

    @Test
    fun testMedianIgnoresOutliers() {
        val gate = RssiRangeGate(
            defaultWindowMs = 1000L,
            defaultMinSamples = 3,
            defaultMethod = RssiRangeGate.DecisionMethod.MEDIAN,
            hysteresisDb = 0
        )

        val addr = "00:11:22:33:44:55"
        gate.clearDevice(addr)

        gate.recordRssi(addr, -90) // outlier
        gate.recordRssi(addr, -60)
        gate.recordRssi(addr, -61)

        // median = -61 -> threshold -70 -> should be in range
        assertTrue(gate.isStableInRange(addr, thresholdRssi = -70), "Median should ignore the -90 outlier and be above -70")
    }
}
