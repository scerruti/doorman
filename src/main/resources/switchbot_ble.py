#!/usr/bin/env python3
import asyncio
import sys
import json
import uuid
from typing import Any, Dict, List, Optional
from bleak import BleakScanner, BleakClient
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

# SwitchBot Bot BLE service / characteristic UUIDs
SWITCHBOT_SERVICE_UUID = "cba20d00-224d-11e6-9fb8-0002a5d5c51b"
SWITCHBOT_CHARACTERISTIC_UUID = "cba20002-224d-11e6-9fb8-0002a5d5c51b"

DISCOVERY_TIMEOUT_SECONDS = 5.0

found_devices: Dict[str, Dict[str, Any]] = {}


def validate_uuid(uuid_string: str, name: str) -> None:
    try:
        parsed = uuid.UUID(uuid_string)
        if str(parsed) != uuid_string.lower():
            raise ValueError(f"{name} must be canonical UUID format: {uuid_string}")
    except ValueError as exc:
        raise ValueError(f"Invalid {name} '{uuid_string}': {exc}") from exc


def build_toggle_command() -> bytes:
    # SwitchBot Bot toggle command payload (no password)
    command = bytes([0x57, 0x01, 0x00])
    validate_switchbot_command(command)
    return command


def validate_switchbot_command(command: bytes) -> None:
    if len(command) != 3:
        raise ValueError("SwitchBot command must be exactly 3 bytes")
    if command[0] != 0x57:
        raise ValueError("SwitchBot command must start with 0x57")
    if command[1] != 0x01:
        raise ValueError("SwitchBot toggle command must use opcode 0x01")
    if command[2] != 0x00:
        raise ValueError("SwitchBot toggle command payload byte must be 0x00")


def is_switchbot_advertisement(device: BLEDevice, advertisement_data: AdvertisementData) -> bool:
    name = (device.name or advertisement_data.local_name or "").lower()
    service_uuids = [uuid.lower() for uuid in (advertisement_data.service_uuids or [])]
    if "switchbot" in name:
        return True
    if SWITCHBOT_SERVICE_UUID in service_uuids:
        return True
    return False


def device_record(device: BLEDevice, advertisement_data: AdvertisementData) -> Dict[str, Any]:
    return {
        "address": device.address,
        "name": device.name or advertisement_data.local_name,
        "rssi": getattr(device, "rssi", None),
        "service_uuids": advertisement_data.service_uuids or [],
        "manufacturer_data": {hex(k): v.hex() for k, v in (advertisement_data.manufacturer_data or {}).items()},
        "switchbot_candidate": is_switchbot_advertisement(device, advertisement_data),
    }


def detection_callback(device: BLEDevice, advertisement_data: AdvertisementData) -> None:
    found_devices[device.address] = device_record(device, advertisement_data)


async def discover_devices() -> None:
    validate_uuid(SWITCHBOT_SERVICE_UUID, "SwitchBot service UUID")
    validate_uuid(SWITCHBOT_CHARACTERISTIC_UUID, "SwitchBot characteristic UUID")

    scanner = BleakScanner(detection_callback)
    await scanner.start()
    await asyncio.sleep(DISCOVERY_TIMEOUT_SECONDS)
    await scanner.stop()

    devices = list(found_devices.values())
    print(json.dumps(devices, ensure_ascii=False, indent=2))


async def send_command(device_address: str, command_hex: str) -> None:
    validate_uuid(SWITCHBOT_SERVICE_UUID, "SwitchBot service UUID")
    validate_uuid(SWITCHBOT_CHARACTERISTIC_UUID, "SwitchBot characteristic UUID")

    if len(command_hex) % 2 != 0:
        raise ValueError("command_hex must contain an even number of hex digits")

    command = bytes.fromhex(command_hex)
    validate_switchbot_command(command)

    async with BleakClient(device_address) as client:
        if not await client.is_connected():
            raise ConnectionError(f"Unable to connect to {device_address}")
        await client.write_gatt_char(SWITCHBOT_CHARACTERISTIC_UUID, command)
        print(json.dumps({
            "address": device_address,
            "command": command.hex(),
            "service_uuid": SWITCHBOT_SERVICE_UUID,
            "characteristic_uuid": SWITCHBOT_CHARACTERISTIC_UUID,
            "status": "sent"
        }, ensure_ascii=False))


async def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python switchbot_ble.py discover | send <address> <command_hex>", file=sys.stderr)
        sys.exit(1)

    action = sys.argv[1].lower()
    if action == "discover":
        await discover_devices()
    elif action == "send" and len(sys.argv) == 4:
        await send_command(sys.argv[2], sys.argv[3])
    else:
        print("Invalid arguments", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
