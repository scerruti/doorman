package com.otabi.doorman

import com.otabi.doorman.platform.Discovery
import com.otabi.doorman.platform.DiscoveryRegistry
import com.otabi.doorman.platform.RssiRangeGate
import com.otabi.doorman.platform.MacBluetoothManager
import com.otabi.doorman.platform.SwitchBotDoorController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.system.exitProcess

// Daemon socket
private const val DAEMON_HOST = "127.0.0.1"
private const val DAEMON_PORT = 9000
private const val NOTIFY_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

// Configure target identification
// If you know the exact MAC you can set TARGET_ADDR; otherwise we resolve from discovery.
private val TARGET_ADDR: String? = null

// Preferred: identify SwitchBot devices by their primary Service UUID, then fingerprint by manufacturer data.
private const val SWITCHBOT_UUID: String = "c706248c-302a-431a-9669-e77c669d2f2d"
// Manufacturer signature (hex substring) to look for inside advHex; set to your device's manufacturer prefix.
private val TARGET_MANUFACTURER_HEX: String? = "0969" // example prefix; replace with real signature (lowercase hex)
// Legacy fallback: optional name substring to match if service+manufacturer resolution fails.
// Recommended: keep null in production; use only for quick local testing.
private val TARGET_NAME_SUBSTRING: String? = null

// Gate / registry tuning
private const val STABLE_WAIT_TIMEOUT_MS: Long = 10_000L
private const val RESOLVE_TIMEOUT_MS: Long = 2_000L

suspend fun main() = coroutineScope {
    val bluetoothManager = MacBluetoothManager()
    val controller = SwitchBotDoorController(bluetoothManager)

    // Event-driven registry and gate
    val registry = DiscoveryRegistry()
    val gate = RssiRangeGate(defaultWindowMs = 1500L, defaultMinSamples = 3, defaultMethod = RssiRangeGate.DecisionMethod.MEDIAN)

    println("=== Doorman Garage Door Controller (SwitchBot BLE) ===")
    println("Starting discovery listener against daemon $DAEMON_HOST:$DAEMON_PORT")

    // Launch discovery listener (updates registry and gate)
    val discoveryJob = launch(Dispatchers.IO) {
        runDiscoveryListener(registry, gate)
    }

    // Resolve target address reactively (non-blocking)
    val resolvedAddr = resolveTargetAddressSuspend(registry, TARGET_ADDR, TARGET_MANUFACTURER_HEX, TARGET_NAME_SUBSTRING, RESOLVE_TIMEOUT_MS)
    if (resolvedAddr == null) {
        println("No target device resolved within ${RESOLVE_TIMEOUT_MS}ms. Ensure device is advertising or set TARGET_ADDR.")
        println("Recent discoveries: ${registry.all().joinToString("\n") { "${it.address} name=${it.name} rssi=${it.rssi} adv=${it.advHex}" }}")
        discoveryJob.cancelAndJoin()
        exitProcess(1)
    }

    println("Target resolved: $resolvedAddr")

    // Wait until gate reports stable in-range
    val stable = waitForStableInRangeSuspend(gate, resolvedAddr, thresholdRssi = -75, timeoutMs = STABLE_WAIT_TIMEOUT_MS)
    if (!stable) {
        println("Device $resolvedAddr did not become stably in range within ${STABLE_WAIT_TIMEOUT_MS}ms. Aborting.")
        discoveryJob.cancelAndJoin()
        exitProcess(2)
    }

    println("Device $resolvedAddr is stably in range. Proceeding with operations.\n")

    // 1. Check initial status
    println("1. Checking initial status:")
    val statusResult = controller.getStatus()
    if (statusResult.isSuccess) {
        println("Status: ${statusResult.getOrNull()}")
    } else {
        println("Error getting status: ${statusResult.exceptionOrNull()?.message}")
    }
    println()

    // 2. Opening door
    println("2. Opening door:")
    val openResult = controller.openDoor()
    if (openResult.isSuccess) {
        println("Door opened successfully")
    } else {
        println("Failed to open door: ${openResult.exceptionOrNull()?.message}")
    }
    println()

    // 3. Status after opening
    println("3. Status after opening:")
    val statusAfterOpen = controller.getStatus()
    if (statusAfterOpen.isSuccess) {
        println("Status: ${statusAfterOpen.getOrNull()}")
    } else {
        println("Error getting status: ${statusAfterOpen.exceptionOrNull()?.message}")
    }
    println()

    // 4. Closing door
    println("4. Closing door:")
    val closeResult = controller.closeDoor()
    if (closeResult.isSuccess) {
        println("Door closed successfully")
    } else {
        println("Failed to close door: ${closeResult.exceptionOrNull()?.message}")
    }
    println()

    // 5. Final status
    println("5. Final status:")
    val finalStatus = controller.getStatus()
    if (finalStatus.isSuccess) {
        println("Status: ${finalStatus.getOrNull()}")
    } else {
        println("Error getting status: ${finalStatus.exceptionOrNull()?.message}")
    }
    println()

    // Cleanup
    discoveryJob.cancelAndJoin()
    println("Done.")
}

/**
 * Discovery listener: connects to daemon, subscribes, parses NOTIFY payloads,
 * updates registry and gate.
 */
private fun runDiscoveryListener(registry: DiscoveryRegistry, gate: RssiRangeGate) {
    try {
        Socket(DAEMON_HOST, DAEMON_PORT).use { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // CONNECT
            output.write(packMsg(0x01, "SwitchBot-KATA".toByteArray(Charsets.UTF_8)))
            output.flush()
            readLengthPrefixedMessage(input) // CONNECT_RSP

            // SUBSCRIBE
            output.write(packMsg(0x03, NOTIFY_UUID.toByteArray(Charsets.UTF_8)))
            output.flush()
            readLengthPrefixedMessage(input) // SUBSCRIBE_RSP

            while (!Thread.currentThread().isInterrupted) {
                val msg = readLengthPrefixedMessage(input) ?: break
                val mtype = msg[0].toInt() and 0xFF
                val payload = msg.copyOfRange(1, msg.size)
                if (mtype == 0x07) { // NOTIFY
                    val d = parseDiscoveryPayload(payload)
                    if (d != null) {
                        // debug: show advHex and extracted MAC for troubleshooting
                        val extractedMac = extractMacFromAdvHex(d.advHex)
                        println("DISCOVERY parsed: addr=${d.address} name=${d.name} rssi=${d.rssi} adv=${d.advHex} extractedMac=$extractedMac")

                        // update registry and gate
                        registry.upsert(Discovery(d.address, d.rssi, d.name, d.advHex))
                        gate.recordRssi(d.address, d.rssi)
                    }
                }
            }
        }
    } catch (e: Exception) {
        println("Discovery listener stopped: ${e.message}")
    }
}

/** Non-blocking resolver: checks snapshot then waits for matching flow emission. */
private suspend fun resolveTargetAddressSuspend(
    registry: DiscoveryRegistry,
    explicitAddr: String?,
    manufacturerHex: String?,
    nameSubstring: String?,
    timeoutMs: Long = 2000L
): String? {
    if (!explicitAddr.isNullOrBlank()) return explicitAddr

    // 1) Service UUID -> Manufacturer data (preferred)
    // Snapshot: find any discovery that advertises the SwitchBot service and contains manufacturer MAC
    findMacFromServiceAndManufacturerSnapshot(registry, SWITCHBOT_UUID, manufacturerHex)?.let { return it }

    // Wait for next matching emission: filter by service UUID then map to extracted MAC from advHex
    val serviceUuidCompact = SWITCHBOT_UUID.replace("-", "")
    val sig = manufacturerHex?.lowercase()
    return withTimeoutOrNull(timeoutMs) {
        registry.discoveryFlow
            .filter { it.advHex.contains(serviceUuidCompact, ignoreCase = true) == true }
            .mapNotNull { adv ->
                // only accept if manufacturer signature present (if provided) and MAC can be extracted
                val advHex = adv.advHex
                if (!sig.isNullOrBlank() && (advHex.contains(sig, ignoreCase = true) != true)) return@mapNotNull null
                extractMacFromAdvHex(advHex)
            }
            .first()
    } ?: run {
        // 2) Fallback: optional name-based resolution (legacy)
        if (!nameSubstring.isNullOrBlank()) {
            registry.findByNameSubstring(nameSubstring)?.let { return it.address }
            registry.bestByRssi(nameSubstring)?.let { return it.address }
            return withTimeoutOrNull(timeoutMs) {
                registry.discoveryFlow
                    .filter { it.name?.contains(nameSubstring, ignoreCase = true) == true }
                    .first()
                    .address
            }
        }
        null
    }
}

/** Snapshot helper: find a discovery that advertises the service UUID and yields a MAC from manufacturer data. */
private fun findMacFromServiceAndManufacturerSnapshot(registry: DiscoveryRegistry, serviceUuid: String, manufacturerHex: String?): String? {
    val compact = serviceUuid.replace("-", "").lowercase()
    val sig = manufacturerHex?.lowercase()
    return registry.all().asSequence()
        .filter { it.advHex.lowercase().contains(compact) == true }
        .mapNotNull { adv ->
            val advHex = adv.advHex
            if (!sig.isNullOrBlank() && (advHex.contains(sig, ignoreCase = true) != true)) return@mapNotNull null
            extractMacFromAdvHex(advHex)
        }
        .firstOrNull()
}

/** Try to extract a 6-byte MAC from advHex. Returns formatted MAC or null. */
private fun extractMacFromAdvHex(advHex: String?): String? {
    if (advHex.isNullOrBlank()) return null
    // Normalize: keep only hex chars
    val hex = advHex.filter { it.isLetterOrDigit() }.lowercase()
    // Find first contiguous 12-hex-digit sequence (6 bytes)
    val match = Regex("([0-9a-f]{12})").find(hex) ?: return null
    val raw = match.groupValues[1]
    // Format as AA:BB:CC:DD:EE:FF
    return raw.chunked(2).joinToString(":") { it.uppercase() }
}

/** Suspend-friendly wait for gate stability. */
suspend fun waitForStableInRangeSuspend(
    gate: RssiRangeGate,
    deviceAddr: String,
    thresholdRssi: Int,
    timeoutMs: Long,
    pollIntervalMs: Long = 150L
): Boolean {
    return withTimeoutOrNull<Boolean>(timeoutMs) {
        while (!gate.isStableInRange(deviceAddr, thresholdRssi)) {
            delay(pollIntervalMs)
        }
        // This is the block's last expression and is a Boolean
        true
    } ?: false
}


/** Read a length-prefixed message from DataInputStream. Returns null on EOF. */
private fun readLengthPrefixedMessage(input: DataInputStream): ByteArray? {
    return try {
        val lenBytes = ByteArray(4)
        input.readFully(lenBytes)
        val len = ByteBuffer.wrap(lenBytes).int
        val body = ByteArray(len)
        input.readFully(body)
        body
    } catch (e: Exception) {
        null
    }
}

/** Pack a length-prefixed message for sending. */
private fun packMsg(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
    val body = ByteArray(1 + payload.size)
    body[0] = type.toByte()
    System.arraycopy(payload, 0, body, 1, payload.size)
    val buf = ByteBuffer.allocate(4 + body.size)
    buf.putInt(body.size)
    buf.put(body)
    return buf.array()
}

/** Parse discovery payload encoded as "addr|rssi|name|adv_hex" */
private data class DiscoveryPayload(val address: String, val rssi: Int, val name: String?, val advHex: String)

private fun parseDiscoveryPayload(payload: ByteArray): DiscoveryPayload? {
    val text = try {
        payload.toString(Charsets.UTF_8)
    } catch (e: Exception) {
        return null
    }
    val parts = text.split("|", limit = 4)
    if (parts.size < 3) return null
    val addr = parts[0].trim()
    val rssi = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
    val name = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
    val advHex = parts.getOrNull(3)?.trim() ?: ""
    return DiscoveryPayload(addr, rssi, name, advHex)
}
