# simulated_door.py
#
# Cleartext simulator for a SwitchBot-style garage door opener.
#
# Responsibilities:
# - Accept cleartext 16-byte command frames
# - Maintain door state (CLOSED/OPENING/OPEN/CLOSING)
# - Queue cleartext notifications for the backend to deliver
# - Advance time deterministically via tick(now)

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Dict, Any, Optional
import time


@dataclass
class DoorState:
    state: str = "CLOSED"          # "CLOSED", "OPENING", "OPEN", "CLOSING"
    transition_end: Optional[float] = None  # timestamp when transition completes


class SimulatedDoor:
    """
    Simulates a SwitchBot Opener using the real device's *cleartext* protocol.

    Public API (device-side):

        write(frame: bytes) -> None
            Called by FakeBleBackend when the client writes to the write characteristic.

        tick(now: float) -> None
            Advance time; handle OPENING/CLOSING completion.

        get_pending_notifications() -> list[bytes]
            Return cleartext notifications for the backend to deliver.
    """

    # Duration (seconds) for OPENING/CLOSING transitions
    TRANSITION_DURATION = 3.0

    def __init__(self, crypto=None, initial_state=None):
        self.crypto = None
        if initial_state:
            self._door = DoorState(
                state=initial_state["state"],
                transition_end=initial_state.get("transition_end")
            )
        else:
            self._door = DoorState()

        self._pending_notifications = []

    # -------------------------------------------------------------------------
    # Public API
    # -------------------------------------------------------------------------

    def write(self, frame: bytes) -> None:
        """
        Accept cleartext 16‑byte command frames directly.

        Real Opener opcodes (byte 8):
            0x00 = status
            0x02 = open
            0x01 = close
        """
        if len(frame) != 16:
            print(f"SimulatedDoor: invalid frame length {len(frame)}", flush=True)
            return

        opcode = frame[8]

        if opcode == 0x00:
            self._handle_status_command()
        elif opcode == 0x02:
            self._handle_open_command()
        elif opcode == 0x01:
            self._handle_close_command()
        else:
            print(f"SimulatedDoor: unknown opcode {opcode}", flush=True)

    def tick(self, now: float) -> None:
        """
        Advance time; complete transitions if needed.
        Backend should call this periodically with a monotonically increasing timestamp.
        """
        if self._door.transition_end is None:
            return

        if now >= self._door.transition_end:
            # Complete the transition
            if self._door.state == "OPENING":
                self._door.state = "OPEN"
                self._queue_notification({
                    "type": "complete",
                    "state": "OPEN",
                    "battery": 100,
                })
            elif self._door.state == "CLOSING":
                self._door.state = "CLOSED"
                self._queue_notification({
                    "type": "complete",
                    "state": "CLOSED",
                    "battery": 100,
                })

            self._door.transition_end = None

    def get_pending_notifications(self) -> List[bytes]:
        """
        Return all cleartext notifications queued since the last call.
        Backend should deliver these to any subscribed clients.
        """
        out = self._pending_notifications
        self._pending_notifications = []
        return out

    # -------------------------------------------------------------------------
    # Command handlers
    # -------------------------------------------------------------------------

    def _handle_open_command(self) -> None:
        if self._door.state in ("OPEN", "OPENING"):
            return

        # Start OPENING transition
        self._door.state = "OPENING"
        self._door.transition_end = time.time() + self.TRANSITION_DURATION

        # Immediate transition notification
        self._queue_notification({
            "type": "transition",
            "state": "OPENING",
            "battery": 100,
        })

    def _handle_close_command(self) -> None:
        if self._door.state in ("CLOSED", "CLOSING"):
            return

        # Start CLOSING transition
        self._door.state = "CLOSING"
        self._door.transition_end = time.time() + self.TRANSITION_DURATION

        # Immediate transition notification
        self._queue_notification({
            "type": "transition",
            "state": "CLOSING",
            "battery": 100,
        })

    def _handle_status_command(self) -> None:
        # Immediate status notification
        self._queue_notification({
            "type": "status",
            "state": self._door.state,
            "battery": 100,
        })

    # -------------------------------------------------------------------------
    # Notification helpers
    # -------------------------------------------------------------------------

    def _queue_notification(self, logical: Dict[str, Any]) -> None:
        """
        Convert a logical notification into a real-device 6‑byte payload.

        Real device format:
            [version=1, battery, 0, 0, state_code, 1]
        """
        state_map = {
            "CLOSED":   0x00,
            "OPEN":     0x01,
            "OPENING":  0x02,
            "CLOSING":  0x03,
        }

        state_code = state_map.get(logical["state"], 0x00)
        battery = logical.get("battery", 100)

        payload = bytes([
            0x01,        # version
            battery,     # battery %
            0x00,        # reserved
            0x00,        # reserved
            state_code,  # state code
            0x01,        # constant tail (matches real device)
        ])

        self._pending_notifications.append(payload)

    # -------------------------------------------------------------------------
    # Introspection (optional)
    # -------------------------------------------------------------------------

    def get_state(self) -> str:
        return self._door.state
