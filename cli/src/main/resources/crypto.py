"""
crypto.py
---------
Full crypto engine for the simulated SwitchBot device.

This module provides:
- Key derivation
- AES encryption/decryption
- Frame parsing/validation
- Command decoding
- Notification encoding

It is intentionally structured so that:
- The simulator does NOT know encryption details
- The backend does NOT know encryption details
- Kotlin and Python can share the same protocol spec
"""

import os
import json
import hashlib
from dataclasses import dataclass
from typing import Dict, Any

from Crypto.Cipher import AES  # pycryptodome


# ============================================================
# CONFIGURATION
# ============================================================

@dataclass
class CryptoConfig:
    token: str
    secret: str
    device_id: str

    @staticmethod
    def from_env():
        token = os.environ.get("SWITCHBOT_TOKEN")
        secret = os.environ.get("SWITCHBOT_SECRET")
        device_id = os.environ.get("SWITCHBOT_DEVICE_ID")

        if not token or not secret or not device_id:
            raise ValueError(
                "SWITCHBOT_TOKEN, SWITCHBOT_SECRET, SWITCHBOT_DEVICE_ID must be set"
            )

        return CryptoConfig(token, secret, device_id)


# ============================================================
# CRYPTO ENGINE
# ============================================================

class SwitchBotCrypto:
    """
    Full crypto engine for the simulated device.

    Responsibilities:
    - Derive AES key
    - Encrypt logical notifications
    - Decrypt incoming commands
    - Validate frame structure
    - Provide a stable interface for the simulator
    """

    HEADER = 0x57  # placeholder until real header is known

    def __init__(self, config: CryptoConfig):
        self.config = config
        self.key = self._derive_key(config.token, config.secret, config.device_id)

    # ------------------------------------------------------------
    # PUBLIC API
    # ------------------------------------------------------------

    def encrypt_command(self, logical: dict) -> bytes:
        """
        Encrypt a logical command dict into a frame the device accepts.
        """
        payload = json.dumps(logical, separators=(",", ":")).encode("utf-8")
        ciphertext = self._encrypt(payload)
        return self._build_frame(ciphertext)

    def decrypt_command(self, frame: bytes) -> Dict[str, Any]:
        """
        Device receives encrypted frame from client.
        Returns a logical command dict.
        """
        ciphertext = self._parse_frame(frame)
        plaintext = self._decrypt(ciphertext)

        try:
            obj = json.loads(plaintext.decode("utf-8"))
        except Exception as e:
            raise ValueError(f"Invalid JSON in decrypted command: {e}")

        if not isinstance(obj, dict) or "cmd" not in obj:
            raise ValueError(f"Invalid command object: {obj}")

        return obj

    def encrypt_notification(self, logical: Dict[str, Any]) -> bytes:
        """
        Device emits logical notification → encrypted frame.
        """
        payload = json.dumps(logical, separators=(",", ":")).encode("utf-8")
        ciphertext = self._encrypt(payload)
        return self._build_frame(ciphertext)

    # ------------------------------------------------------------
    # KEY DERIVATION
    # ------------------------------------------------------------

    def _derive_key(self, token: str, secret: str, device_id: str) -> bytes:
        """
        Derive AES-128 key.
        This is a placeholder until the real SwitchBot derivation is known.
        """
        material = (token + secret + device_id).encode("utf-8")
        digest = hashlib.sha256(material).digest()
        return digest[:16]  # AES-128

    # ------------------------------------------------------------
    # AES ENCRYPTION
    # ------------------------------------------------------------

    def _encrypt(self, plaintext: bytes) -> bytes:
        cipher = AES.new(self.key, AES.MODE_ECB)
        padded = self._pkcs7_pad(plaintext, 16)
        return cipher.encrypt(padded)

    def _decrypt(self, ciphertext: bytes) -> bytes:
        cipher = AES.new(self.key, AES.MODE_ECB)
        padded = cipher.decrypt(ciphertext)
        return self._pkcs7_unpad(padded, 16)

    @staticmethod
    def _pkcs7_pad(data: bytes, block_size: int) -> bytes:
        pad_len = block_size - (len(data) % block_size)
        return data + bytes([pad_len] * pad_len)

    @staticmethod
    def _pkcs7_unpad(data: bytes, block_size: int) -> bytes:
        if not data or len(data) % block_size != 0:
            raise ValueError("Invalid padded data length")
        pad_len = data[-1]
        if pad_len < 1 or pad_len > block_size:
            raise ValueError("Invalid padding length")
        if data[-pad_len:] != bytes([pad_len] * pad_len):
            raise ValueError("Invalid padding bytes")
        return data[:-pad_len]

    # ------------------------------------------------------------
    # FRAME FORMAT
    # ------------------------------------------------------------

    def _build_frame(self, ciphertext: bytes) -> bytes:
        """
        Placeholder frame format:
            [ HEADER, length, ...ciphertext... ]
        Replace with real SwitchBot frame structure later.
        """
        if len(ciphertext) > 255:
            raise ValueError("Ciphertext too long for placeholder frame")

        return bytes([self.HEADER, len(ciphertext)]) + ciphertext

    def _parse_frame(self, frame: bytes) -> bytes:
        if len(frame) < 2:
            raise ValueError("Frame too short")

        if frame[0] != self.HEADER:
            raise ValueError(f"Invalid header: {frame[0]:02x}")

        length = frame[1]
        if len(frame) != 2 + length:
            raise ValueError(
                f"Frame length mismatch: expected {2+length}, got {len(frame)}"
            )

        return frame[2:]

# ============================================================
# DEVICE FILE LOADER
# ============================================================

def load_crypto_from_device_file(device_address: str) -> CryptoConfig:
    """
    Load token/secret/device_id from the real_devices.json file.
    Requires BLE_DEVICE_FILE to be set.
    """
    path = os.environ.get("BLE_DEVICE_FILE")
    if not path:
        raise ValueError("BLE_DEVICE_FILE must be set to real_devices.json")

    with open(path, "r") as f:
        data = json.load(f)

    if device_address not in data:
        raise KeyError(f"Device {device_address} not found in {path}")

    entry = data[device_address]

    return CryptoConfig(
        token=entry["token"],
        secret=entry["secret"],
        device_id=entry["device_id"]
    )

# ============================================================
# HIGH-LEVEL COMMAND BUILDER
# ============================================================

def build_encrypted_frame(device_address: str, cmd_name: str) -> bytes:
    """
    Build an encrypted frame for a high-level command name:
        - "status"
        - "open"
        - "close"

    Uses SWITCHBOT_TOKEN, SWITCHBOT_SECRET, SWITCHBOT_DEVICE_ID
    from environment variables.
    """
    config = load_crypto_from_device_file(device_address)
    crypto = SwitchBotCrypto(config)

    logical = {"cmd": cmd_name}
    return crypto.encrypt_command(logical)
