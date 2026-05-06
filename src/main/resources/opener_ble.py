#!/usr/bin/env python3
import sys
import json
import asyncio

from protocol import (
    NOTIFY_CHAR_UUID,
    SERVICE_UUID,
    WRITE_CHAR_UUID,
    validate_command,
)

from ble_backend import get_ble_backend

WRITE_UUID = "cba20002-224d-11e6-9fb8-0002a5d5c51b"
DISCOVERY_TIMEOUT_SECONDS = 5.0
found_devices = {}

PREBUILT = {
    "pb_status": bytes.fromhex("570f31000000050100020000010000e3"),
    "pb_open":   bytes.fromhex("570f31000000050102020000010000e5"),
    "pb_close":  bytes.fromhex("570f31000000050101020000010000e4"),
}

def detection_callback(device, advertisement_data):
    found_devices[device.address] = {
        "address": device.address,
        "name": device.name or advertisement_data.local_name,
        "rssi": getattr(device, "rssi", None),
        "service_uuids": advertisement_data.service_uuids or [],
        "manufacturer_data": {
            hex(k): v.hex()
            for k, v in (advertisement_data.manufacturer_data or {}).items()
        },
        "opener_candidate": 0x0969 in (advertisement_data.manufacturer_data or {}),
    }


async def discover(backend):
    await backend.discover(detection_callback, DISCOVERY_TIMEOUT_SECONDS)
    print(json.dumps(list(found_devices.values()), indent=2))


async def send(backend, device, cmd):
    # Prebuilt commands for testing
    if cmd in PREBUILT:
        frame = PREBUILT[cmd]

    # Future: real crypto commands
    elif cmd in ("status", "open", "close"):
        from crypto import build_encrypted_frame
        frame = build_encrypted_frame(device, cmd)

    # Raw hex fallback
    else:
        frame = bytes.fromhex(cmd)

    await backend.send(device, WRITE_UUID, frame)


async def listen(backend, address):
    print("LISTEN STARTED, BACKEND =", type(backend).__name__, flush=True)

    def on_notify(data: bytes):
        print("OPENER NOTIFY:", data.hex(), flush=True)

    await backend.register_notification_handler(address, NOTIFY_CHAR_UUID, on_notify)

    print(f"Listening for notifications from {address}... Press Ctrl+C to stop.")
    try:
        while True:
            await asyncio.sleep(1)
    except KeyboardInterrupt:
        print("Stopped listening.")



async def main():
    if len(sys.argv) < 2:
        print("Usage: opener_ble.py discover | send <address> <command_hex> | listen <address>")
        sys.exit(1)

    backend = get_ble_backend()
    action = sys.argv[1].lower()

    if action == "discover":
        await discover(backend)

    elif action == "send":
        if len(sys.argv) != 4:
            print("Usage: opener_ble.py send <address> <command_hex>")
            sys.exit(1)
        await send(backend, sys.argv[2], sys.argv[3])

    elif action == "listen":
        if len(sys.argv) != 3:
            print("Usage: opener_ble.py listen <address>")
            sys.exit(1)
        await listen(backend, sys.argv[2])

    else:
        print(f"Unknown command: {action}")
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
