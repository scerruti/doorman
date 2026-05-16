import asyncio
from bleak import BleakClient, BleakScanner

WRITE_UUID = "0000fd01-0000-1000-8000-00805f9b34fb"
NOTIFY_UUID = "0000fd02-0000-1000-8000-00805f9b34fb"


def _get_loop():
    try:
        loop = asyncio.get_event_loop()
    except RuntimeError:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
    return loop


def run_async(coro):
    loop = _get_loop()
    return loop.run_until_complete(coro)


class BleakBackend:
    def __init__(self):
        self.clients = {}                 # mac -> BleakClient
        self.notification_callbacks = {}  # mac -> callback

    # ---------- async internals ----------

    async def _scan_async(self, timeout: float = 5.0):
        devices = await BleakScanner.discover(timeout=timeout)
        return [(d.address, d.name) for d in devices]

    async def _connect_async(self, mac: str):
        if mac in self.clients and self.clients[mac].is_connected:
            return True

        client = BleakClient(mac)
        await client.connect()
        self.clients[mac] = client
        return True

    async def _disconnect_async(self, mac: str):
        client = self.clients.get(mac)
        if client and client.is_connected:
            await client.disconnect()
        self.clients.pop(mac, None)

    async def _write_async(self, mac: str, data: bytes):
        client = self.clients.get(mac)
        if not client or not client.is_connected:
            raise RuntimeError(f"Not connected to {mac}")
        await client.write_gatt_char(WRITE_UUID, data, response=True)

    async def _subscribe_notifications_async(self, mac: str, callback):
        client = self.clients.get(mac)
        if not client or not client.is_connected:
            raise RuntimeError(f"Not connected to {mac}")

        def _handler(_sender, payload: bytearray):
            callback(bytes(payload))

        await client.start_notify(NOTIFY_UUID, _handler)
        self.notification_callbacks[mac] = callback

    # ---------- sync API used by harness ----------

    def scan(self, timeout: float = 5.0):
        return run_async(self._scan_async(timeout))

    def connect(self, mac: str) -> bool:
        return run_async(self._connect_async(mac))

    def disconnect(self, mac: str):
        return run_async(self._disconnect_async(mac))

    def write(self, mac: str, data: bytes):
        return run_async(self._write_async(mac, data))

    def subscribe_notifications(self, mac: str, callback):
        return run_async(self._subscribe_notifications_async(mac, callback))
