# ============================================================
# SwitchBot Opener — Cleartext Protocol Definitions
# ============================================================
import struct

# BLE UUIDs
WRITE_UUID  = "cba20002-224d-11e6-9fb8-0002a5d5c51b" #
NOTIFY_UUID = "cba20003-224d-11e6-9fb8-0002a5d5c51b" #

# SwitchBot Relay Switch — single toggle command (Kotlin guards against wrong-state sends)
TOGGLE_FRAME = bytes([0x57, 0x01, 0x01]) + bytes(13)
STATUS_FRAME = bytes([0x57, 0x02])       + bytes(14)


# GDO State Mapping: Bridges human-readable states with the hardware's byte codes.
# This ensures consistency between decoding real traffic and encoding simulated data.
STATE_TO_CODE = {
    "CLOSED": 0x00, #
    "OPEN": 0x01, #
    "OPENING": 0x02, #
    "CLOSING": 0x03, #
}

# Inverse mapping for convenient lookups during decoding
CODE_TO_STATE = {v: k for k, v in STATE_TO_CODE.items()} #


def decode_notify(data: bytes):
    """
    Decodes the 6‑byte notification payload from the Opener.
    Example: 01 64 00 00 03 01
    """
    if len(data) < 6:
        return {"raw": data.hex()}

    version = data[0]
    battery = data[1]
    state_code = data[4]

    return {
        "version": version,
        "battery": battery,
        "state_code": state_code,
        "state": CODE_TO_STATE.get(state_code, f"UNKNOWN_{state_code}"), #
        "raw": data.hex(),
    }


def encode_notify_frame(state_name: str, battery: int = 100) -> bytes:
    """
    Constructs the 6-byte GDO notification payload.
    Format: [Version][Battery][?][?][State][?]
    """
    state_code = STATE_TO_CODE.get(state_name, 0xFF) #
    # Pack as 6 bytes: ver, batt, 0, 0, state, 1
    return struct.pack("BBBBBB", 0x01, battery, 0x00, 0x00, state_code, 0x01) #
