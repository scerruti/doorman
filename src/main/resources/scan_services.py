import asyncio
from bleak import BleakClient

ADDRESS = "1B59B01F-CCF0-7266-7928-5FD143F42BD6"

async def main():
    async with BleakClient(ADDRESS) as client:
        # Just connecting is enough; Bleak populates client.services internally
        services = client.services
        for service in services:
            print(f"SERVICE {service.uuid}")
            for char in service.characteristics:
                print(f"  CHAR {char.uuid} props={char.properties}")

asyncio.run(main())
