# ============================================================
# SwitchBot Opener — Simple CLI for Real or Simulated Backends
# ============================================================

import argparse
import asyncio
import os

from opener_client import OpenerClient
from ble_backend import get_ble_backend as real_backend
from fake_ble_backend import get_fake_backend as fake_backend


def select_backend():
    """
    Selects backend based on environment variable BLE_BACKEND.
    BLE_BACKEND=real  → real BLE backend
    BLE_BACKEND=fake  → simulated door backend
    Default: real
    """
    mode = os.environ.get("BLE_BACKEND", "real").lower()

    if mode == "fake":
        print("Using simulated BLE backend")
        backend = fake_backend()
        return backend


    print("Using real BLE backend")
    return real_backend()


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("address", help="BLE address of the opener (or fake device name)")
    parser.add_argument("command", choices=["open", "close", "status", "listen"])
    args = parser.parse_args()

    backend = select_backend()
    client = OpenerClient(backend, args.address)

    # Always listen so notifications appear
    await client.listen()

    if args.command == "listen":
        # Keep process alive
        while True:
            await asyncio.sleep(1)

    # Execute the requested command
    action = getattr(client, args.command)
    await action()

    # Keep alive briefly so notifications can arrive
    await asyncio.sleep(1)


if __name__ == "__main__":
    asyncio.run(main())
