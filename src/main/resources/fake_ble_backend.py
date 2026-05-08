# fake_ble_backend.py
#
# Fake BLE backend that:
# - Exposes an Android-like BLE API to the Python BLE layer
# - Routes writes to SimulatedDoor instances
# - Pulls encrypted notifications from each device
# - Delivers notifications to registered subscribers
# - Supports multiple devices
# - Is deterministic (no threads inside SimulatedDoor)

import time
from typing import Dict, Callable, List
import asyncio

from pathlib import Path

from simulated_door import SimulatedDoor
from crypto import SwitchBotCrypto, CryptoConfig

import json
import os

def load_sim_devices(path="sim_devices.json"):
    """
    Load simulated devices from the JSON file.
    Returns a list of device dicts.
    """
    base = Path(__file__).parent
    full_path = base / path

    if not full_path.exists():
        print(f"FakeBleBackend: no {full_path} found, starting with no simulated devices")
        return []

    try:
        with open(full_path, "r") as f:
            data = json.load(f)
        return data.get("devices", [])
    except Exception as e:
        print(f"FakeBleBackend: failed to load {full_path}: {e}")
        return []


class FakeBleBackend:
    """
    A BLE backend that simulates Android BLE behavior.

    Responsibilities:
    - Maintain a registry of simulated devices
    - Handle connect/disconnect
    - Handle write_characteristic
    - Deliver notifications to subscribers
    - Drive device time via tick()
    """

    def __init__(self):
        # device_id (MAC) → SimulatedDoor
        self.devices: Dict[str, SimulatedDoor] = {}

        # device_id → list of notification callbacks
        self.notification_subscribers: Dict[str, List[Callable[[bytes], None]]] = {}

        # Track connected devices
        self.connected: Dict[str, bool] = {}

        # Alias → MAC
        self.alias_map: Dict[str, str] = {}

        # Load simulated devices
        for entry in load_sim_devices():
            mac = entry["mac"]
            alias = entry.get("alias")
            initial_state = {
                "state": entry.get("state", "CLOSED"),
                "transition_end": entry.get("transition_end"),
            }

            # Create the simulated device (crypto ignored in cleartext mode)
            self.devices[mac] = SimulatedDoor(initial_state=initial_state)
            self.notification_subscribers[mac] = []
            self.connected[mac] = False

            if alias:
                self.alias_map[alias] = mac

        print(f"FakeBleBackend: loaded {len(self.devices)} simulated device(s)")

    # -------------------------------------------------------------------------
    # Background tick loop and notification pump
    # -------------------------------------------------------------------------
    async def _run_tick_loop(self):
        """
        Background loop that drives device time and notifications.
        Runs at ~20 Hz (every 50 ms).
        """
        while True:
            self.tick()
            await asyncio.sleep(0.05)

    # -------------------------------------------------------------------------
    # Alias resolution
    # -------------------------------------------------------------------------
    def resolve(self, address: str) -> str:
        """
        Resolve alias → MAC if needed.
        If address is already a MAC, return it unchanged.
        """
        return self.alias_map.get(address, address)

    # -------------------------------------------------------------------------
    # Device registration
    # -------------------------------------------------------------------------

    def register_simulated_device(self, mac, token, secret, device_id):
        """
        Create a new simulated device instance with its own crypto engine.
        """
        config = CryptoConfig(token=token, secret=secret, device_id=device_id)
        crypto = SwitchBotCrypto(config)
        self.devices[mac] = SimulatedDoor(crypto)
        self.notification_subscribers[mac] = []
        self.connected[mac] = False

    # ============================================================
    # New unified backend interface for OpenerClient
    # ============================================================

    async def start(self):
        """
        Start the automatic tick loop for the simulated backend.
        Real BLE backends don't need this, but the simulator does.
        """
        if hasattr(self, "_tick_task"):
            return  # already running

        self._tick_task = asyncio.create_task(self._run_tick_loop())

    async def send(self, address: str, uuid: str, data: bytes):
        """
        Adapter for the new OpenerClient interface.
        Forwards writes into the existing simulated write path.
        """
        address = self.resolve(address)
        # Ensure device is "connected"
        if not self.connected.get(address, False):
            self.connect(address)

        self.tick()

        # Use the existing write_characteristic method
        self.write_characteristic(address, uuid, data)


    async def register_notification_handler(self, address: str, uuid: str, callback):
        """
        Adapter for the new OpenerClient interface.
        Registers a callback using the existing subscription system.
        """
        address = self.resolve(address)
        # Ensure device exists
        if address not in self.devices:
            print(f"FakeBleBackend: unknown device {address}")
            return

        # Use the existing subscription mechanism
        self.subscribe_notifications(address, callback)

    # -------------------------------------------------------------------------
    # Android-like BLE API
    # -------------------------------------------------------------------------

    def connect(self, device_id: str) -> bool:
        """
        Simulate BLE connect.
        """
        if device_id not in self.devices:
            return False
        self.connected[device_id] = True
        return True

    def disconnect(self, device_id: str) -> None:
        """
        Simulate BLE disconnect.
        """
        if device_id in self.connected:
            self.connected[device_id] = False

    def write_characteristic(self, device_id: str, uuid: str, data: bytes) -> None:
        """
        Simulate BLE write to a characteristic.
        """
        if not self.connected.get(device_id, False):
            print(f"FakeBleBackend: write to {device_id} while disconnected")
            return

        device = self.devices.get(device_id)
        if not device:
            print(f"FakeBleBackend: unknown device {device_id}")
            return

        # Forward encrypted bytes to the simulated device
        device.write(data)
        self.save_state()

    def subscribe_notifications(self, device_id: str, callback: Callable[[bytes], None]):
        """
        Register a notification callback for a device.
        """
        if device_id not in self.notification_subscribers:
            self.notification_subscribers[device_id] = []
        self.notification_subscribers[device_id].append(callback)

    # -------------------------------------------------------------------------
    # Time + notification pump
    # -------------------------------------------------------------------------

    def tick(self):
        """
        Drive device time and deliver notifications.
        Should be called periodically by the Python BLE API.
        """
        now = time.time()

        for device_id, device in self.devices.items():
            # Advance device state machine
            device.tick(now)

            # Deliver pending cleartext notifications
            pending = device.get_pending_notifications()
            if not pending:
                continue

            for payload in pending:
                for callback in self.notification_subscribers.get(device_id, []):
                    callback(payload)

        self.save_state()

    def save_state(self):
        base = Path(__file__).parent
        full_path = base / "sim_devices.json"

        data = {"devices": []}

        for mac, device in self.devices.items():
            entry = {
                "mac": mac,
                "alias": None,
                "state": device._door.state,
                "transition_end": device._door.transition_end,
            }

            # Preserve alias if you loaded one
            for alias, mapped_mac in self.alias_map.items():
                if mapped_mac == mac:
                    entry["alias"] = alias
                    break

            data["devices"].append(entry)

        with open(full_path, "w") as f:
            json.dump(data, f, indent=2)

# -------------------------------------------------------------------------
# Factory function for CLI
# -------------------------------------------------------------------------

_backend = None

def get_fake_backend():
    global _backend
    if _backend is None:
        _backend = FakeBleBackend()
    return _backend