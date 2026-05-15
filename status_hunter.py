import asyncio
from bleak import BleakScanner

# Your GDO MAC
ADDRESS = "1B59B01F-CCF0-7266-7928-5FD143F42BD6"

async def monitor_status():
    print(f"Monitoring advertisements for {ADDRESS}...")
    print("Move the door/magnet now and watch for changes below.")
    
    def detection_callback(device, advertisement_data):
        if device.address == ADDRESS:
            # We want to see the raw bytes in the service_data or manufacturer_data
            mfr_data = advertisement_data.manufacturer_data
            svc_data = advertisement_data.service_data
            
            print(f"RSSI: {advertisement_data.rssi} | Mfr Data: {mfr_data if mfr_data else 'None'}")
            for uuid, data in svc_data.items():
                print(f"  Service {uuid}: {data.hex()}")

    scanner = BleakScanner(detection_callback)
    await scanner.start()
    await asyncio.sleep(60) # Monitor for 1 minute
    await scanner.stop()

if __name__ == "__main__":
    asyncio.run(monitor_status())