package com.otabi.doorman.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DoorStateTracker(
    private val scope: CoroutineScope,
    private val travelTimeMs: Long = 15000L // Approximate time for the motor to fully traverse the track
) {
    private val _syntheticState = MutableStateFlow(DoorStatus.UNKNOWN)
    val syntheticState: StateFlow<DoorStatus> = _syntheticState.asStateFlow()

    private var rawHardwareState = DoorStatus.UNKNOWN
    private var motionJob: Job? = null

    /**
     * Feeds raw hardware states from BLE advertisements into the tracker.
     */
    fun updateHardwareState(newState: DoorStatus) {
        if (rawHardwareState == newState) return
        rawHardwareState = newState

        // If the magnet is touching, the door is definitively CLOSED.
        if (newState == DoorStatus.CLOSED) {
            motionJob?.cancel()
            _syntheticState.value = DoorStatus.CLOSED
        } 
        // If the magnet is separated (OPEN), but we aren't currently tracking a transition,
        // we assume the door is parked in the fully OPEN position.
        else if (newState == DoorStatus.OPEN && _syntheticState.value !in listOf(DoorStatus.OPENING, DoorStatus.CLOSING)) {
            _syntheticState.value = DoorStatus.OPEN
        }
    }

    /**
     * Called the moment we receive an ACK (Type 0x01) from the radio confirming the relay pulsed.
     */
    fun onCommandAcknowledged() {
        motionJob?.cancel()

        when (_syntheticState.value) {
            DoorStatus.CLOSED -> {
                _syntheticState.value = DoorStatus.OPENING
                startMotionTimer(DoorStatus.OPEN)
            }
            DoorStatus.OPEN -> {
                _syntheticState.value = DoorStatus.CLOSING
                startMotionTimer(DoorStatus.CLOSED)
            }
            DoorStatus.OPENING -> { // Button pressed while opening usually stops/reverses it
                _syntheticState.value = DoorStatus.CLOSING
                startMotionTimer(DoorStatus.CLOSED)
            }
            DoorStatus.CLOSING -> { // Button pressed while closing usually reverses it to open
                _syntheticState.value = DoorStatus.OPENING
                startMotionTimer(DoorStatus.OPEN)
            }
            DoorStatus.UNKNOWN -> {
                // Fallback guess based on current hardware magnet
                _syntheticState.value = if (rawHardwareState == DoorStatus.OPEN) DoorStatus.CLOSING else DoorStatus.OPENING
                startMotionTimer(if (rawHardwareState == DoorStatus.OPEN) DoorStatus.CLOSED else DoorStatus.OPEN)
            }
        }
    }

    private fun startMotionTimer(targetState: DoorStatus) {
        motionJob = scope.launch {
            delay(travelTimeMs)
            // If we were traveling OPEN, assume we reached the top of the track when the timer expires.
            if (targetState == DoorStatus.OPEN) _syntheticState.value = DoorStatus.OPEN
        }
    }
}