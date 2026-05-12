package com.otabi.doorman.macos

import com.otabi.doorman.core.ParsedAdvertisement
import com.otabi.doorman.core.DeviceResolver
import com.otabi.doorman.platform.Discovery
import com.otabi.doorman.platform.DiscoveryRegistry
import com.otabi.doorman.platform.RssiRangeGate
import com.otabi.doorman.platform.MacBluetoothManager
import com.otabi.doorman.platform.SwitchBotDoorController
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.system.exitProcess

// Daemon socket
private const val DAEMON_HOST = "127.0.0.1"
private const val DAEMON_PORT = 9000
private const val NOTIFY_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

// Gate / registry tuning
private const val STABLE_WAIT_TIMEOUT_MS: Long = 10_000L

suspend fun main(): Unit = coroutineScope {
    val gdoMac = loadGdoMac()

    val bluetoothManager = MacBluetoothManager()
    val controller = SwitchBotDoorController(bluetoothManager)

    // Event-driven registry and gate
    val registry = DiscoveryRegistry()
    val gate = RssiRangeGate(defaultWindowMs = 5000L, defaultMinSamples = 3, defaultMethod = RssiRangeGate.DecisionMethod.MEDIAN)

    println("=== Doorman Garage Door Controller (SwitchBot BLE) ===")
    println("Starting discovery listener against daemon $DAEMON_HOST:$DAEMON_PORT")

    // Launch discovery listener (updates registry and gate)
    val discoveryJob = launch(Dispatchers.IO) {
        runDiscoveryListener(registry, gate)
    }

    var resolvedAddr: String? = null

    while (true) {
        println("\n=== Doorman Test Menu ===")
        println("1. Resolve target device")
        println("2. Wait for stable in-range")
        println("3. Get status")
        println("4. Open door")
        println("5. Close door")
        println("6. Show discovered devices")
        println("7. Quit")
        print("Select option: ")

        val rawInput = readlnOrNull()
        if (rawInput == null) {
            println("\n[Error] Standard input closed.")
            break
        }
        val input = rawInput.trim()
        if (input.isEmpty()) continue

        when (input) {
           "1" -> {
                println("Resolving GDO by MAC ($gdoMac)...")
                resolvedAddr = resolveGdoAddress(registry, gdoMac)
                if (resolvedAddr == null) {
                    println("Failed to resolve GDO from current discoveries.")
                } else {
                    println("GDO resolved at address: $resolvedAddr")
                }
            }
            "2" -> {
                if (resolvedAddr == null) {
                    println("Please resolve target device first (Option 1).")
                } else {
                    println("Waiting up to ${STABLE_WAIT_TIMEOUT_MS}ms for stable in-range for $resolvedAddr...")
                    val stats = gate.getRecentStats(resolvedAddr)
                    println("Current Signal: ${stats?.median ?: "No Data"} dBm (Target: -100)")
                    val stable = DeviceResolver.waitForStableInRangeSuspend(gate, resolvedAddr, thresholdRssi = -100, timeoutMs = STABLE_WAIT_TIMEOUT_MS)
                    if (stable) {
                        println("Device is stably in range.")
                    } else {
                        println("Device did not become stably in range.")
                    }
                }
            }
            "3" -> {
                if (resolvedAddr == null) {
                    println("Error: You must Resolve Target (Option 1) before checking status.")
                } else {
                    println("Checking status for $resolvedAddr...")
                    val statusResult = controller.getStatus(resolvedAddr)
                    statusResult.onSuccess { status ->
                        println("Status: $status")
                    }.onFailure { error ->
                        println("Error getting status: ${error.message}")
                    }
                }
            }
            "4" -> {
                println("Opening door...")
                val openResult = controller.openDoor(resolvedAddr)
                if (openResult.isSuccess) {
                    println("Door opened successfully")
                } else {
                    println("Failed to open door: ${openResult.exceptionOrNull()?.message}")
                }
            }
            "5" -> {
                println("Closing door...")
                val closeResult = controller.closeDoor(resolvedAddr)
                if (closeResult.isSuccess) {
                    println("Door closed successfully")
                } else {
                    println("Failed to close door: ${closeResult.exceptionOrNull()?.message}")
                }
            }
           "6" -> {
                println("Discovered devices:")
                registry.all().forEach {
                    println("${it.address} name=${it.name} rssi=${it.rssi} adv=${it.advHex}")
                }
            }
            "7", "q", "quit" -> {
                println("Exiting.")
                break
            }
            else -> println("Invalid choice.")
        }
    }

    discoveryJob.cancel()
    println("Done.")
    exitProcess(0)
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

            // SUBSCRIBE (to keep the connection alive/tested)
            output.write(packMsg(0x03, NOTIFY_UUID.toByteArray(Charsets.UTF_8)))
            output.flush()
            readLengthPrefixedMessage(input) // SUBSCRIBE_RSP

            while (!Thread.currentThread().isInterrupted) {
                val msg = readLengthPrefixedMessage(input) ?: break
                val mtype = msg[0].toInt() and 0xFF
                val payload = msg.copyOfRange(1, msg.size)
                if (mtype == 0x07) { // NOTIFY
                    // Strip the Kotlin padding byte
                    val actualData = if (payload.isNotEmpty() && payload[0] == 0x00.toByte()) payload.copyOfRange(1, payload.size) else payload
                    
                    if (actualData.size == 6) {
                        // This is a SwitchBot status frame
                        val stateCode = actualData[4].toInt() and 0xFF
                        val stateStr = when (stateCode) {
                            0x00 -> "CLOSED"
                            0x01 -> "OPEN"
                            0x02 -> "OPENING"
                            0x03 -> "CLOSING"
                            else -> "UNKNOWN ($stateCode)"
                        }
                        print("\n\n[Async Event] 🚪 Garage Door is now: $stateStr\nSelect option: ")
                    } else {
                        // This is a regular discovery payload
                        val d = parseDiscoveryPayload(payload)
                        if (d != null) {
                            registry.upsert(Discovery(d.address, d.rssi, d.name, d.advHex))
                            gate.recordRssi(d.address, d.rssi)
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        println("Discovery listener stopped: ${e.message}")
    }
}

// === BOUNDARY MAPPING: Mac Simulator -> Core Domain ===
private fun resolveGdoAddress(registry: DiscoveryRegistry, targetMac: String): String? {
    return registry.all().firstOrNull { rec ->
        // 1. Convert the Bleak string into raw bytes
        val rawBytes = hexStringToByteArraySafe(rec.advHex)
        
        // 2. Map the dirty platform data into the clean Domain object
        val adv = ParsedAdvertisement(
            address = rec.address,
            name = rec.name,
            manufacturerData = if (rawBytes.isNotEmpty()) mapOf(0x0969 to rawBytes) else emptyMap()
        )
        
        // 3. Ask the agnostic Core if it's a match
        DeviceResolver.isTarget(adv, targetMac)
    }?.address
}

/** Utility: convert hex string to byte array safely. */
private fun hexStringToByteArraySafe(s: String): ByteArray {
    val clean = s.replace("\\s+".toRegex(), "")
    if (clean.length % 2 != 0) return ByteArray(0)
    return try {
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        }
        out
    } catch (e: Exception) {
        ByteArray(0)
    }
}

// === IO HELPERS ===
private fun readLengthPrefixedMessage(input: DataInputStream): ByteArray? {
    return try {
        val lenBytes = ByteArray(4)
        input.readFully(lenBytes)
        val len = ByteBuffer.wrap(lenBytes).int
        val body = ByteArray(len)
        input.readFully(body)
        body
    } catch (e: Exception) { null }
}

private fun packMsg(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
    val body = ByteArray(1 + payload.size)
    body[0] = type.toByte()
    System.arraycopy(payload, 0, body, 1, payload.size)
    val buf = ByteBuffer.allocate(4 + body.size)
    buf.putInt(body.size)
    buf.put(body)
    return buf.array()
}

private data class DiscoveryPayload(val address: String, val rssi: Int, val name: String?, val advHex: String)

private fun parseDiscoveryPayload(payload: ByteArray): DiscoveryPayload? {
    // 1. Strip the 0x00 message type byte off the front
    val actualData = if (payload.isNotEmpty()) payload.copyOfRange(1, payload.size) else payload
    
    // 2. Convert the clean data to a String
    val text = try { String(actualData, Charsets.UTF_8) } catch (e: Exception) { return null }
    
    val parts = text.split("|", limit = 4)
    if (parts.size < 3) return null
    
    val addr = parts[0].trim()
    val rssi = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
    val name = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() && it != "Unknown" }
    val advHex = parts.getOrNull(3)?.trim() ?: ""
    
    return DiscoveryPayload(addr, rssi, name, advHex)
}

private fun loadGdoMac(): String {
    return try {
        val props = java.util.Properties()
        val file = java.io.File("device-secrets.properties")
        props.load(file.inputStream())
        props.getProperty("switchbot.mac.address", "")
    } catch (e: Exception) { "" }
}