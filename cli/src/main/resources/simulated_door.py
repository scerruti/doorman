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

import asyncio
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

    DEFAULT_TRANSITION_DURATION = 15.0

    def __init__(self, address, name, rssi=-70, adv="0969aabbccddeeff", crypto=None, initial_state=None, transition_duration=None):
        self.address = address
        self.name = name
        self.rssi = rssi
        self.adv = adv
        self._last_adv_time = 0
        self._transition_duration = transition_duration if transition_duration is not None else self.DEFAULT_TRANSITION_DURATION

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
        Accept 16-byte SwitchBot Relay Switch command frames.

        Relay protocol (frame[0] == 0x57):
            frame[1] == 0x01, frame[2] == 0x01  →  relay ON  (open door)
            frame[1] == 0x01, frame[2] == 0x00  →  relay OFF (close door)
            frame[1] == 0x02                    →  get status
        """
        if len(frame) < 2 or frame[0] != 0x57:
            print(f"SimulatedDoor: unrecognised frame {frame.hex()}", flush=True)
            return

        cmd = frame[1]
        if cmd == 0x01:
            # Single toggle: open if closed/closing, close if open/opening
            if self._door.state in ("CLOSED", "CLOSING"):
                self._handle_open_command()
            else:
                self._handle_close_command()
        elif cmd == 0x02:
            self._handle_status_command()
        else:
            print(f"SimulatedDoor: unknown command byte {cmd:#04x}", flush=True)

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
        self._door.state = "OPENING"
        self._door.transition_end = time.time() + self._transition_duration
        # No immediate notification — hardware only reports final OPEN/CLOSED state.
        # DoorStateTracker infers OPENING synthetically from the command acknowledgement.

    def _handle_close_command(self) -> None:
        if self._door.state in ("CLOSED", "CLOSING"):
            return
        self._door.state = "CLOSING"
        self._door.transition_end = time.time() + self._transition_duration
        # No immediate notification — hardware only reports final OPEN/CLOSED state.

    def _handle_status_command(self) -> None:
        state = self._door.state
        # Report physical state: OPENING is still CLOSED, CLOSING is still OPEN
        physical = "OPEN" if state in ("OPEN", "CLOSING") else "CLOSED"
        self._queue_notification({"type": "status", "state": physical, "battery": 100})

    # -------------------------------------------------------------------------
    # Notification helpers
    # -------------------------------------------------------------------------

    def _queue_notification(self, logical: Dict[str, Any]) -> None:
        """
        6-byte payload. State is in LSB of byte 4: 0x00 = CLOSED, 0x01 = OPEN.
        OPENING/CLOSING are synthetic Kotlin states — hardware only reports OPEN/CLOSED.
        """
        state_map = {
            "OPEN":   0x00,
            "CLOSED": 0x01,
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

    async def run_heartbeat(self, send_callback):
        """
        The internal engine of the door. 
        Advances time and pushes notifications to the daemon.
        """
        print(f"SimulatedDoor ({self.name}): Engine started.", flush=True)
        while True:
            now = time.time()
            self.tick(now)
            
            # 1. Periodic Discovery "Shout" (Every 5 seconds)
            if now - self._last_adv_time >= 5.0:
                discovery_str = f"{self.address}|{self.rssi}|{self.name}|{self.adv}"
                # Send as a pipe-delimited string (Type 0x07)
                send_callback(discovery_str.encode("utf-8"))
                self._last_adv_time = now

            # 2. State-Change Notifications (Transition/Complete/Status)
            for payload in self.get_pending_notifications():
                send_callback(payload)
                
            await asyncio.sleep(0.5)