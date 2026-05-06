# python_ble_api.py
#
# Android-like BLE API facade for Python.
#
# Goals:
# - Present a stable, Android-style BLE surface to the rest of your system
# - Hide whether we're talking to FakeBleBackend (sim) or BleakBackend (real)
# - Stay completely ignorant of SwitchBot / protocol / encryption
#
# This layer only knows:
#   - device_id (MAC or logical ID)
#   - characteristic UUIDs
#   - raw bytes

from __future__ import annotations

import os
from typing import Callable, Optional, Protocol


# ---------------------------------------------------------------------------
# Backend protocol (what FakeBleBackend and BleakBackend must implement)
# ---------------------------------------------------------------------------

class BleBackend(Protocol):
    def connect(self, device_id: str) -> bool: ...
    def disconnect(self, device_id: str) -> None: ...
    def write_characteristic(self, device_id: str, uuid: str, data: bytes) -> None: ...
    def subscribe_notifications(self, device_id: str, callback: Callable[[bytes], None]) -> None: ...
    # Optional for real BLE backends; required for FakeBleBackend
    def tick(self) -> None: ...


# ---------------------------------------------------------------------------
# Python BLE API facade
# ---------------------------------------------------------------------------

class PythonBleApi:
    """
    Android-like BLE API facade.

    This is the only thing the rest of your Python/Kotlin bridge should talk to.
    It does NOT know:
      - SwitchBot
      - Encryption
      - Commands
      - Frames

    It only knows:
      - connect / disconnect
      - write_characteristic
      - subscribe_notifications
      - tick (for simulated time)
    """

    def __init__(self, backend: BleBackend):
        self._backend = backend

    # -----------------------------------------------------------------------
    # Android-like API
    # -----------------------------------------------------------------------

    def connect(self, device_id: str) -> bool:
        return self._backend.connect(device_id)

    def disconnect(self, device_id: str) -> None:
        self._backend.disconnect(device_id)

    def write_characteristic(self, device_id: str, uuid: str, data: bytes) -> None:
        """
        Equivalent to Android's BluetoothGatt.writeCharacteristic:
        - device_id: MAC or logical ID
        - uuid: characteristic UUID string
        - data: raw bytes (already encrypted by Kotlin)
        """
        self._backend.write_characteristic(device_id, uuid, data)

    def subscribe_notifications(self, device_id: str, callback: Callable[[bytes], None]) -> None:
        """
        Equivalent to setCharacteristicNotification + onCharacteristicChanged.
        Callback receives raw bytes (still encrypted).
        """
        self._backend.subscribe_notifications(device_id, callback)

    def tick(self) -> None:
        """
        Drive backend time (for FakeBleBackend / simulators).
        Real BLE backends may implement this as a no-op.
        """
        tick = getattr(self._backend, "tick", None)
        if callable(tick):
            tick()


# ---------------------------------------------------------------------------
# Factory: choose backend based on environment
# ---------------------------------------------------------------------------

def create_ble_api(
    fake_backend_factory: Callable[[], BleBackend],
    real_backend_factory: Callable[[], BleBackend],
    env_var: str = "BLE_BACKEND",
) -> PythonBleApi:
    """
    Create a PythonBleApi using either the fake or real backend.

    BLE_BACKEND=fake  -> FakeBleBackend (simulated)
    BLE_BACKEND=real  -> BleakBackend (real BLE)
    default           -> fake (safer for dev)
    """
    mode = os.environ.get(env_var, "fake").lower()

    if mode == "real":
        backend = real_backend_factory()
    else:
        backend = fake_backend_factory()

    return PythonBleApi(backend)
