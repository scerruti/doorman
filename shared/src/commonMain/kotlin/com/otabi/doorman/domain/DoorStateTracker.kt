package com.otabi.doorman.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tracks door state for a Relay‑based SwitchBot garage add‑on.
 *
 * Hardware only reports:
 *   - OPEN  (magnet separated)
 *   - CLOSED (magnet touching)
 *
 * OPENING and CLOSING are synthetic states based on timing.
 */
class DoorStateTracker(
    private val scope: CoroutineScope,
    private val travelTimeMs: Long = 15000L
) {
    private val _syntheticState = MutableStateFlow(DoorStatus.UNKNOWN)
    val syntheticState: StateFlow<DoorStatus> = _syntheticState.asStateFlow()

    private var rawHardwareState = DoorStatus.UNKNOWN
    private var motionJob: Job? = null

    /**
     * Called whenever a BLE notification reports the magnet sensor state.
     * This is the *authoritative* hardware state.
     */
    fun updateHardwareState(newState: DoorStatus) {
        if (rawHardwareState == newState) return
        rawHardwareState = newState

        when (newState) {
            DoorStatus.CLOSED -> {
                // Door reached the floor — stop motion and finalize state
                motionJob?.cancel()
                _syntheticState.value = DoorStatus.CLOSED
            }

            DoorStatus.OPEN -> {
                // Door reached the top — stop motion and finalize state
                motionJob?.cancel()
                _syntheticState.value = DoorStatus.OPEN
            }

            else -> Unit
        }
    }

    /**
     * Called when the relay pulse is acknowledged (TX notification received).
     * This means "the button was pressed".
     */
    fun onCommandAcknowledged() {
        motionJob?.cancel()

        val current = _syntheticState.value

        when (current) {
            DoorStatus.CLOSED -> {
                // Start opening
                _syntheticState.value = DoorStatus.OPENING
                startMotionTimer(DoorStatus.OPEN)
            }

            DoorStatus.OPEN -> {
                // Start closing
                _syntheticState.value = DoorStatus.CLOSING
                startMotionTimer(DoorStatus.CLOSED)
            }

            DoorStatus.OPENING -> {
                // Button press while opening → reverse direction
                _syntheticState.value = DoorStatus.CLOSING
                startMotionTimer(DoorStatus.CLOSED)
            }

            DoorStatus.CLOSING -> {
                // Button press while closing → reverse direction
                _syntheticState.value = DoorStatus.OPENING
                startMotionTimer(DoorStatus.OPEN)
            }

            DoorStatus.UNKNOWN -> {
                // Infer direction from hardware state
                if (rawHardwareState == DoorStatus.OPEN) {
                    _syntheticState.value = DoorStatus.CLOSING
                    startMotionTimer(DoorStatus.CLOSED)
                } else {
                    _syntheticState.value = DoorStatus.OPENING
                    startMotionTimer(DoorStatus.OPEN)
                }
            }
        }
    }

    /**
     * Starts a synthetic motion timer. If hardware does not report OPEN/CLOSED
     * before the timer expires, we assume the door reached the target.
     */
    private fun startMotionTimer(targetState: DoorStatus) {
        motionJob = scope.launch {
            delay(travelTimeMs)

            // Only update if hardware hasn't already reported the final state
            if (rawHardwareState != targetState) {
                _syntheticState.value = targetState
            }
        }
    }
}
