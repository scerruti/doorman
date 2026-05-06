# protocol.py
#
# Clean, Pythonic SwitchBot Garage Door Opener protocol definitions.

from enum import Enum

SERVICE_UUID = "cba20d00-224d-11e6-9fb8-0002a5d5c51b"
WRITE_CHAR_UUID = "cba20002-224d-11e6-9fb8-0002a5d5c51b"
NOTIFY_CHAR_UUID = "cba20003-224d-11e6-9fb8-0002a5d5c51b"

class OpenerCommand(Enum):
    OPEN   = b"\x57\x01\x01\x00\x00\x00"
    CLOSE  = b"\x57\x01\x00\x00\x00\x00"
    STATUS = b"\x57\x02\x00\x01\x00\xf6"


# Set of all valid commands for quick membership testing
VALID_COMMANDS = {cmd.value for cmd in OpenerCommand}


def validate_command(frame: bytes) -> OpenerCommand:
    """
    Validate a 6‑byte SwitchBot Garage Door Opener command frame.
    Returns the OpenerCommand enum if valid.
    Raises ValueError if invalid.
    """
    if len(frame) != 6:
        raise ValueError(f"Invalid frame length {len(frame)} (expected 6)")

    if frame not in VALID_COMMANDS:
        raise ValueError(f"Unknown opener command: {frame.hex()}")

    # Return the enum for convenience
    return OpenerCommand(frame)
