import asyncio
from bleak import BleakClient

ADDRESS = "1B59B01F-CCF0-7266-7928-5FD143F42BD6"

async def list_services():
    async with BleakClient(ADDRESS) as client:
        print(f"Connected: {client.is_connected}")
        for service in client.services:
            print(f"\n[Service] {service}")
            for char in service.characteristics:
                print(f"  [Characteristic] {char} ({','.join(char.properties)})")

asyncio.run(list_services())