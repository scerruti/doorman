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
    private val travelTimeMs: Long = 15000L,
    private val failsafeTimeMs: Long = travelTimeMs + travelTimeMs / 2
) {
    private val _syntheticState = MutableStateFlow(DoorStatus.UNKNOWN)
    val syntheticState: StateFlow<DoorStatus> = _syntheticState.asStateFlow()

    private var rawHardwareState = DoorStatus.UNKNOWN
    private var motionJob: Job? = null
    private var transitionStartMs: Long = 0L

    /**
     * Called whenever a BLE notification reports the magnet sensor state.
     * This is the *authoritative* hardware state.
     */
    fun updateHardwareState(newState: DoorStatus) {
        rawHardwareState = newState

        val current = _syntheticState.value
        val msSinceTransition = System.currentTimeMillis() - transitionStartMs

        when {
            // OPENING + OPEN: hold animation for full travel time, ignore hardware confirmation.
            current == DoorStatus.OPENING && newState == DoorStatus.OPEN -> return

            // OPENING + CLOSED within grace: stale scan packet, ignore.
            current == DoorStatus.OPENING && newState == DoorStatus.CLOSED && msSinceTransition < 2000L -> return

            // OPENING + CLOSED after grace: door didn't open — report failure.
            current == DoorStatus.OPENING && newState == DoorStatus.CLOSED -> {
                motionJob?.cancel()
                _syntheticState.value = DoorStatus.CLOSED
            }

            // CLOSING + OPEN: sensor stays OPEN throughout travel, ignore.
            current == DoorStatus.CLOSING && newState == DoorStatus.OPEN -> return

            // CLOSING + CLOSED: door confirmed closed (possibly early), accept it.
            current == DoorStatus.CLOSING && newState == DoorStatus.CLOSED -> {
                motionJob?.cancel()
                _syntheticState.value = DoorStatus.CLOSED
            }

            else -> when (newState) {
                DoorStatus.CLOSED -> { motionJob?.cancel(); _syntheticState.value = DoorStatus.CLOSED }
                DoorStatus.OPEN   -> { motionJob?.cancel(); _syntheticState.value = DoorStatus.OPEN }
                else -> Unit
            }
        }
    }

    /**
     * Called when the relay pulse is acknowledged (TX notification received).
     * This means "the button was pressed".
     */
    fun onCommandAcknowledged() {
        transitionStartMs = System.currentTimeMillis()
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
        // OPENING uses travelTimeMs — drives the animation; OPEN is suppressed from hardware.
        // CLOSING uses failsafeTimeMs — hardware CLOSED is the primary resolution; this is the fallback.
        val delay = if (targetState == DoorStatus.OPEN) travelTimeMs else failsafeTimeMs
        motionJob = scope.launch {
            delay(delay)
            _syntheticState.value = targetState
        }
    }
}
