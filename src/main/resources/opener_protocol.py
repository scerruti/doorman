# ============================================================
# SwitchBot Opener — Cleartext Protocol Definitions
# ============================================================

# BLE UUIDs
WRITE_UUID  = "cba20002-224d-11e6-9fb8-0002a5d5c51b"
NOTIFY_UUID = "cba20003-224d-11e6-9fb8-0002a5d5c51b"

# Fixed 16‑byte command frames extracted from your Android capture
STATUS_FRAME = bytes.fromhex("570f31000000050100020000010000e3")
OPEN_FRAME   = bytes.fromhex("570f31000000050102020000010000e5")
CLOSE_FRAME  = bytes.fromhex("570f31000000050101020000010000e4")

# Notification decoder
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

    state_map = {
        0x00: "CLOSED",
        0x01: "OPEN",
        0x02: "OPENING",
        0x03: "CLOSING",
    }

    return {
        "version": version,
        "battery": battery,
        "state_code": state_code,
        "state": state_map.get(state_code, f"UNKNOWN_{state_code}"),
        "raw": data.hex(),
    }
