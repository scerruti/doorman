import asyncio
import logging
from bleak import BleakClient
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

# --- CONFIGURATION (UPDATED FROM YOUR DISCOVERY) ---
DEVICE_ADDR = "1B59B01F-CCF0-7266-7928-5FD143F42BD6"
KEY = bytes.fromhex("2c3ed4a4f9cfa20ddc5f60c3c48850dd")
KEY_ID = bytes.fromhex("51")

# The real UUIDs from your MacBook scan
WRITE_UUID  = "cba20002-224d-11e6-9fb8-0002a5d5c51b"
NOTIFY_UUID = "cba20003-224d-11e6-9fb8-0002a5d5c51b"

# Try "Relay On" (0x57 0x01 0x01) if the GDO command (0x57 0x0F) fails
CMD_OPEN = bytes([0x57, 0x01, 0x01]) + b'\x00' * 13 
CMD_GET_STATUS = bytes([0x57, 0x02]) + b'\x00' * 14

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("GDO_FIX")

class GDOTester:
    def __init__(self):
        self.event = asyncio.Event()

    def encrypt(self, data):
        iv = KEY_ID + b'\x00' * 15
        cipher = Cipher(algorithms.AES(KEY), modes.CTR(iv))
        return KEY_ID + cipher.encryptor().update(data)

    def decrypt(self, data):
        if len(data) < 17: return data
        iv = data[0:1] + b'\x00' * 15
        cipher = Cipher(algorithms.AES(KEY), modes.CTR(iv))
        return cipher.decryptor().update(data[1:])

    def notification_handler(self, sender, data):
        decrypted = self.decrypt(data)
        logger.info(f"🚩 RESPONSE: {decrypted.hex().upper()}")
        self.event.set()

    async def run(self):
        logger.info(f"Connecting to {DEVICE_ADDR}...")
        async with BleakClient(DEVICE_ADDR) as client:
            # Establishing the notify link often triggers the OS Pairing prompt
            await client.start_notify(NOTIFY_UUID, self.notification_handler)
            
            logger.info("Step 1: Requesting Status...")
            await client.write_gatt_char(WRITE_UUID, self.encrypt(CMD_GET_STATUS))
            await asyncio.sleep(1)

            logger.info("Step 2: Sending OPEN command...")
            await client.write_gatt_char(WRITE_UUID, self.encrypt(CMD_OPEN))
            
            # Wait 5 seconds to see if a notification comes back
            try:
                await asyncio.wait_for(self.event.wait(), timeout=5.0)
                logger.info("✅ Received a response from the door!")
            except asyncio.TimeoutError:
                logger.warning("No response, but command was sent.")
            
            await asyncio.sleep(2)
            await client.stop_notify(NOTIFY_UUID)

if __name__ == "__main__":
    asyncio.run(GDOTester().run())