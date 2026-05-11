package com.otabi.doorman.platform

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * RssiRangeGate
 *
 * Maintains per-device RSSI history and exposes stable-in-range checks with hysteresis.
 *
 * Place this file at:
 *   src/main/kotlin/com/otabi/doorman/platform/RssiRangeGate.kt
 */
class RssiRangeGate(
    private val defaultWindowMs: Long = 1500L,
    private val defaultMinSamples: Int = 3,
    private val defaultMethod: DecisionMethod = DecisionMethod.AVERAGE,
    private val hysteresisDb: Int = 2
) {

    enum class DecisionMethod { AVERAGE, MEDIAN }

    private data class Sample(val ts: Long, val rssi: Int)

    // Per-device sample deque
    private val samples = ConcurrentHashMap<String, ArrayDeque<Sample>>()

    // Lightweight per-device locks to synchronize deque operations
    private val locks = ConcurrentHashMap<String, Any>()

    // Last decision per device for hysteresis
    private val lastDecision = ConcurrentHashMap<String, Boolean>()

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun getLock(addr: String): Any = locks.computeIfAbsent(addr) { Any() }

    /**
     * Record a new RSSI sample for a device.
     * Call from your discovery callback whenever you receive an RSSI sample.
     */
    fun recordRssi(deviceAddr: String, rssi: Int, tsMs: Long = nowMs()) {
        val dq = samples.computeIfAbsent(deviceAddr) { ArrayDeque() }
        val lock = getLock(deviceAddr)
        synchronized(lock) {
            dq.addLast(Sample(tsMs, rssi))
            // cap size to avoid unbounded growth
            if (dq.size > 200) {
                while (dq.size > 150) dq.removeFirst()
            }
        }
    }

    /**
     * Evaluate whether the device has been stably in range.
     *
     * @param deviceAddr device address (key used when recording samples)
     * @param thresholdRssi threshold in dBm (e.g., -75)
     * @param windowMs sliding window length in milliseconds
     * @param minSamples minimum number of samples required to make a decision
     * @param method decision method: AVERAGE or MEDIAN
     */
    fun isStableInRange(
        deviceAddr: String,
        thresholdRssi: Int = -75,
        windowMs: Long = defaultWindowMs,
        minSamples: Int = defaultMinSamples,
        method: DecisionMethod = defaultMethod
    ): Boolean {
        val dq = samples[deviceAddr] ?: return false
        val lock = getLock(deviceAddr)
        val cutoff = nowMs() - windowMs

        val recent: List<Int> = synchronized(lock) {
            // prune old samples
            while (dq.isNotEmpty() && dq.first.ts < cutoff) dq.removeFirst()
            dq.map { it.rssi }
        }

        if (recent.size < minSamples) return false

        val metric = when (method) {
            DecisionMethod.AVERAGE -> recent.average()
            DecisionMethod.MEDIAN -> {
                val sorted = recent.sorted()
                val mid = sorted.size / 2
                if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
            }
        }

        val last = lastDecision[deviceAddr] ?: false
        // Hysteresis semantics: when last==false (out), require threshold - H to enter;
        // when last==true (in), require threshold + H to leave.
        val effectiveThreshold = if (last) thresholdRssi + hysteresisDb else thresholdRssi - hysteresisDb
        val decision = metric >= effectiveThreshold

        lastDecision[deviceAddr] = decision
        return decision
    }

    /**
     * Convenience: record a sample and immediately evaluate stability.
     * Useful to call inline from discovery callbacks.
     */
    fun recordAndCheck(
        deviceAddr: String,
        rssi: Int,
        thresholdRssi: Int = -75,
        windowMs: Long = defaultWindowMs,
        minSamples: Int = defaultMinSamples,
        method: DecisionMethod = defaultMethod
    ): Boolean {
        recordRssi(deviceAddr, rssi)
        return isStableInRange(deviceAddr, thresholdRssi, windowMs, minSamples, method)
    }

    /**
     * Clear stored samples and decision state for a device.
     */
    fun clearDevice(deviceAddr: String) {
        samples.remove(deviceAddr)
        locks.remove(deviceAddr)
        lastDecision.remove(deviceAddr)
    }

    /**
     * Clear all tracked devices.
     */
    fun clearAll() {
        samples.clear()
        locks.clear()
        lastDecision.clear()
    }

    /**
     * Optional helper: get a simple summary of recent stats for debugging.
     * Returns null if no samples exist.
     */
    fun getRecentStats(deviceAddr: String, windowMs: Long = defaultWindowMs): RecentStats? {
        val dq = samples[deviceAddr] ?: return null
        val lock = getLock(deviceAddr)
        val cutoff = nowMs() - windowMs
        val recent: List<Int> = synchronized(lock) {
            while (dq.isNotEmpty() && dq.first.ts < cutoff) dq.removeFirst()
            dq.map { it.rssi }
        }
        if (recent.isEmpty()) return null
        val avg = recent.average()
        val median = run {
            val s = recent.sorted()
            val mid = s.size / 2
            if (s.size % 2 == 1) s[mid].toDouble() else (s[mid - 1] + s[mid]) / 2.0
        }
        return RecentStats(count = recent.size, average = avg.roundToInt(), median = median.roundToInt())
    }

    data class RecentStats(val count: Int, val average: Int, val median: Int)
}
