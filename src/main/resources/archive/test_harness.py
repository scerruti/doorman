# test_harness.py
#
# Unified test harness for:
# - configuring simulated doors
# - configuring encryption
# - driving FakeBleBackend or real BLE
# - sending encrypted commands
# - receiving encrypted notifications
#
# This is the Python-side "lab environment" for your protocol.

from __future__ import annotations

import json
import os
import time
from typing import Dict, Callable, Optional

from python_ble_api import PythonBleApi, create_ble_api
from fake_ble_backend import FakeBleBackend
from bleak_backend import BleakBackend   

from crypto import CryptoConfig, SwitchBotCrypto


# ---------------------------------------------------------------------------
# TestHarness
# ---------------------------------------------------------------------------

class TestHarness:
    """
    High-level utility for:
      - configuring simulated doors
      - configuring encryption
      - sending encrypted commands
      - receiving encrypted notifications
      - driving time for FakeBleBackend

    This is the Python-side equivalent of an Android test environment.
    """

    WRITE_UUID = "0000fd01-0000-1000-8000-00805f9b34fb"  # placeholder

    def __init__(self, use_fake_backend=True, registry_path="sim_devices.json"):       
        """
        use_fake_backend=True → FakeBleBackend (simulated BLE)
        use_fake_backend=False → BleakBackend (real BLE)
        """
        if use_fake_backend:
            backend = FakeBleBackend()
        else:
            backend = BleakBackend()  

        self.ble = PythonBleApi(backend)
        self.backend = backend

        # device_id → SwitchBotCrypto
        self.crypto_engines: Dict[str, SwitchBotCrypto] = {}

        # device_id → list of received encrypted notifications
        self.notifications: Dict[str, list[bytes]] = {}

        # device_id → MAC
        self.device_id_to_mac = {}

        script_dir = os.path.dirname(os.path.abspath(__file__))
        self.registry_path = os.path.join(script_dir, registry_path)
        self._load_persistent_devices()

    # -----------------------------------------------------------------------
    # Device configuration
    # -----------------------------------------------------------------------

    def add_simulated_door(self, mac: str, device_id: str, token: str, secret: str):
        config = CryptoConfig(token=token, secret=secret, device_id=device_id)
        crypto = SwitchBotCrypto(config)

        self.crypto_engines[mac] = crypto
        self.notifications[mac] = []
        self.device_id_to_mac[device_id] = mac   # NEW

        if isinstance(self.backend, FakeBleBackend):
            self.backend.register_simulated_device(mac, token, secret, device_id)

        self.ble.subscribe_notifications(
            mac,
            lambda data, m=mac: self._on_notification(m, data)
        )

    def resolve_mac(self, user_input: str) -> str:
        """
        Accept either MAC or device_id and return the MAC.
        """
        # If user entered a MAC directly
        if user_input in self.crypto_engines:
            return user_input

        # If user entered a device_id
        if user_input in self.device_id_to_mac:
            return self.device_id_to_mac[user_input]

        raise KeyError(f"Unknown device or MAC: {user_input}")


    # -----------------------------------------------------------------------
    # Notification handling
    # -----------------------------------------------------------------------

    def _on_notification(self, device_id: str, encrypted_frame: bytes):
        """
        Store encrypted notifications for later inspection.
        """
        self.notifications[device_id].append(encrypted_frame)

    # -----------------------------------------------------------------------
    # Command sending
    # -----------------------------------------------------------------------

    def send_command(self, device_id: str, logical_cmd: dict):
        """
        Convert a logical command dict into an encrypted frame and send it.
        Example logical_cmd:
            { "cmd": "open" }
        """
        crypto = self.crypto_engines[device_id]
        encrypted = crypto.encrypt_command(logical_cmd)  # same format as device expects
        self.ble.write_characteristic(device_id, self.WRITE_UUID, encrypted)

    # -----------------------------------------------------------------------
    # Time driving
    # -----------------------------------------------------------------------

    def tick(self):
        """
        Drive simulated time (FakeBleBackend only).
        """
        self.ble.tick()

    # -----------------------------------------------------------------------
    # Helpers for tests
    # -----------------------------------------------------------------------

    def get_notifications(self, device_id: str) -> list[bytes]:
        """
        Return and clear notifications for a device.
        """
        out = self.notifications[device_id]
        self.notifications[device_id] = []
        return out
    
    def decode_notification(self, device_id: str, encrypted_frame: bytes) -> dict:
        """
        Decrypt a notification frame into a logical dict.
        """
        crypto = self.crypto_engines[device_id]
        ciphertext = crypto._parse_frame(encrypted_frame)
        plaintext = crypto._decrypt(ciphertext)
        return json.loads(plaintext.decode("utf-8"))
    
    def get_simulator_state(self, device_id: str) -> str:
        """
        Return the internal state of the simulated door.
        """
        if hasattr(self.backend, "devices"):
            sim = self.backend.devices.get(device_id)
            if sim:
                return sim.get_state()
        return "UNKNOWN"

    def connect(self, device_id: str):
        return self.ble.connect(device_id)

    def disconnect(self, device_id: str):
        self.ble.disconnect(device_id)

    def _load_persistent_devices(self):
        if not os.path.exists(self.registry_path):
            return

        with open(self.registry_path, "r") as f:
            data = json.load(f)

        for mac, cfg in data.items():
            self.add_simulated_door(
                mac=mac,
                device_id=cfg["device_id"],
                token=cfg["token"],
                secret=cfg["secret"]
            )


    def save_persistent_devices(self):
        data = {}
        for mac, crypto in self.crypto_engines.items():
            data[mac] = {
                "token": crypto.config.token,
                "secret": crypto.config.secret,
                "device_id": crypto.config.device_id
            }

        with open(self.registry_path, "w") as f:
            json.dump(data, f, indent=2)
