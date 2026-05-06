import asyncio
from bleak import BleakClient

addr = "1B59B01F-CCF0-7266-7928-5FD143F42BD6"

async def main():
    print("Connecting...")
    async with BleakClient(addr) as client:
        print("Connected!")
        print("Services:", client.services)

asyncio.run(main())
