import os
import json
import asyncio
import time
import threading
from pathlib import Path

from bleak import BleakScanner, BleakClient
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

NOTIFY_UUID = "cba20003-224d-11e6-9fb8-0002a5d5c51b"

# ============================================================
# BASE BACKEND INTERFACE
# ============================================================

class BLEBackend:
    async def discover(self, callback, timeout: float):
        raise NotImplementedError

    async def send(self, address: str, char_uuid: str, data: bytes):
        raise NotImplementedError

    async def register_notification_handler(self, address, char_uuid, callback):
        """
        Register a notification handler for the given characteristic.
        The callback must accept a single bytes argument: callback(data: bytes).
        """
        raise NotImplementedError


# ============================================================
# REAL BLE BACKEND
# ============================================================

class RealBleBackend(BLEBackend):
    async def discover(self, callback, timeout: float):
        scanner = BleakScanner(callback)
        await scanner.start()
        await asyncio.sleep(timeout)
        await scanner.stop()

    async def send(self, address, write_uuid, data):
        async with BleakClient(address) as client:

            # 1. Subscribe to NOTIFY characteristic to force macOS service discovery
            try:
                await client.start_notify(
                    NOTIFY_UUID,
                    lambda *_: None
                )
            except Exception:
                pass  # ignore if notify char isn't available yet

            # 2. Now the write characteristic exists
            await client.write_gatt_char(write_uuid, data)

    async def register_notification_handler(self, address, char_uuid, callback):
        """
        Real backend: use Bleak notifications.
        We spawn a background task that:
        - connects to the device
        - registers a notify handler
        - keeps the connection alive
        """

        async def _runner():
            async with BleakClient(address) as client:
                if not client.is_connected:
                    raise ConnectionError(f"Unable to connect to {address}")

                # Bleak notify callback: (sender, data)
                def _bleak_cb(_sender, data: bytes):
                    callback(data)

                await client.start_notify(char_uuid, _bleak_cb)

                # Keep connection alive
                try:
                    while True:
                        await asyncio.sleep(1)
                finally:
                    try:
                        await client.stop_notify(char_uuid)
                    except Exception:
                        pass

        asyncio.create_task(_runner())


# ============================================================
# BACKEND FACTORY
# ============================================================

def get_ble_backend():
    return RealBleBackend()
