# ============================================================
# SwitchBot Opener — Unified Client (Real + Simulated Backends)
# ============================================================

from opener_protocol import (
    WRITE_UUID,
    NOTIFY_UUID,
    STATUS_FRAME,
    OPEN_FRAME,
    CLOSE_FRAME,
    decode_notify,
)

class OpenerClient:
    """
    High-level client for controlling the SwitchBot Opener.

    Works with:
      - ble_backend.BLEBackend (real BLE)
      - fake_ble_backend.FakeBLEBackend (simulated door)
    """

    def __init__(self, backend, address: str):
        self.backend = backend
        self.address = address
        self.last_notify = None

    # -------------------------------
    # Internal send helper
    # -------------------------------
    async def _send(self, frame: bytes):
        await self.backend.send(self.address, WRITE_UUID, frame)

    # -------------------------------
    # Public commands
    # -------------------------------
    async def status(self):
        await self._send(STATUS_FRAME)

    async def open(self):
        await self._send(OPEN_FRAME)

    async def close(self):
        await self._send(CLOSE_FRAME)

    # -------------------------------
    # Notification subscription
    # -------------------------------
    async def listen(self, callback=None):
        """
        Register a notification handler.

        If callback is None, decoded notifications are printed.
        """

        def handler(data: bytes):
            decoded = decode_notify(data)
            self.last_notify = decoded

            if callback:
                callback(decoded)
            else:
                print("NOTIFY:", decoded, flush=True)

        await self.backend.register_notification_handler(
            self.address,
            NOTIFY_UUID,
            handler,
        )
