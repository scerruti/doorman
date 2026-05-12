package com.otabi.doorman.platform

import com.otabi.doorman.domain.BluetoothConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SocketConnectionAdapter(
    private val client: BleSocketClient
) : BluetoothConnection {

    // Satisfies: suspend fun sendCommand(command: ByteArray)
    override suspend fun sendCommand(command: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        val deferredAck = CompletableDeferred<Unit>()
        val originalCallback = client.onWriteAck

        try {
            // Temporarily intercept the write acknowledgment
            client.onWriteAck = { ack ->
                deferredAck.complete(Unit)
                originalCallback?.invoke(ack)
            }

            // Send the bytes to the Python daemon
            client.writeWithResponse(command)

            // Suspend until the daemon replies with the 0x06 WRITE_RSP
            val result = withTimeoutOrNull(3000L) { deferredAck.await() }

            if (result != null) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Command timeout: No ACK received from daemon"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // Ensure we don't leave dangling hooks
            client.onWriteAck = originalCallback
        }
    }

    // Satisfies the interface requirement for reading data
    override suspend fun readData(): Result<ByteArray> {
        // Since BleSocketClient uses callbacks (push), 
        // a pull-based read is not supported here.
        return Result.failure(Exception("Pull-based read not supported. Use the notify callback."))
    }

    // Note: Removed 'suspend' because the interface doesn't use it here
    override fun disconnect() {
        // We do nothing to keep the socket pipe open
    }
}