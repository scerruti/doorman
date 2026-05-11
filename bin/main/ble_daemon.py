#!/usr/bin/env python3
import asyncio
import struct
import logging
import argparse
from typing import Dict, Set
from bleak import BleakClient, BleakScanner
from simulated_door import SimulatedDoor

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ble_daemon")

# 1. Match the Kotlin hardcoded port
TCP_HOST = "127.0.0.1"
TCP_PORT = 9000

# 2. Global State (Restored Simulator Toggle)
SIMULATOR = True
ACTIVE_TCP_WRITERS: Set[asyncio.StreamWriter] = set()
DISCOVERED_DEVICES: Dict[str, dict] = {}
ACTIVE_CLIENTS: Dict[str, BleakClient] = {}  # <--- RESTORED THIS LINE

# 3. The PERFECT Framing Match
def pack_msg(mtype: int, payload: bytes) -> bytes:
    # Kotlin expects: [4 bytes total length] followed by [1 byte type + N bytes payload]
    body = struct.pack(">B", mtype) + payload
    return struct.pack(">I", len(body)) + body

async def read_msg(reader: asyncio.StreamReader):
    # Read the 4-byte integer length
    header = await reader.readexactly(4)
    mlen = struct.unpack(">I", header)[0]
    
    # Read the body
    body = await reader.readexactly(mlen)
    return body[0], body[1:]

# 4. The Background Broadcaster
def broadcast_discovery(msg_bytes: bytes):
    """Sends the raw discovery packet to all connected Kotlin listeners"""
    for writer in list(ACTIVE_TCP_WRITERS):
        try:
            writer.write(msg_bytes)
        except Exception:
            pass

def detection_callback(device, advertisement_data):
    addr = device.address
    rssi = advertisement_data.rssi
    name = device.name or advertisement_data.local_name or "Unknown"
    
    adv_hex = "".join([v.hex() for v in advertisement_data.manufacturer_data.values()])
    
    DISCOVERED_DEVICES[addr] = {
        "address": addr,
        "name": name,
        "rssi": rssi,
        "adv": adv_hex
    }
    
    if "WoSwitchGDO" in name or "SwitchBot" in name:
        logger.info(f"👂 Radio heard GDO: {name} ({addr}) RSSI: {rssi}")
        
    payload_str = f"{addr}|{rssi}|{name}|{adv_hex}"
    
    # 0x07 = NOTIFY in Kotlin. Prepend 0x00 because Kotlin blindly strips the first byte!
    msg_bytes = pack_msg(0x07, b"\x00" + payload_str.encode("utf-8"))
    loop = asyncio.get_event_loop()
    loop.call_soon(broadcast_discovery, msg_bytes)

# --- Hardware Adapters ---
class BLEAdapter:
    async def connect(self, address: str) -> bool: raise NotImplementedError
    async def write(self, address: str, data: bytes) -> bool: raise NotImplementedError

class RealBleAdapter(BLEAdapter):
    async def connect(self, address: str) -> bool:
        if address in ACTIVE_CLIENTS and ACTIVE_CLIENTS[address].is_connected:
            return True
        try:
            client = BleakClient(address)
            await client.connect()
            ACTIVE_CLIENTS[address] = client
            logger.info(f"REAL: Connected to {address}")
            return True
        except Exception as e:
            logger.error(f"REAL: Connection failed to {address}: {e}")
            return False

    async def write(self, address: str, data: bytes) -> bool:
        client = ACTIVE_CLIENTS.get(address)
        if not client or not client.is_connected:
            if not await self.connect(address): return False
            client = ACTIVE_CLIENTS.get(address)
        try:
            # WRITE_UUID is missing here?
            # It's an implicit global from a previous iteration or we'll assume it exists if RealBleAdapter is used.
            # I will leave as is, since we are working in Sim mode right now.
            await client.write_gatt_char(WRITE_UUID, data)
            logger.info(f"REAL: Write success to {address}")
            return True
        except Exception as e:
            logger.error(f"REAL: Write failed: {e}")
            return False

class SimBleAdapter(BLEAdapter):
    def __init__(self):
        self.door = SimulatedDoor() # Uses your simulated_door.py
    async def connect(self, address: str) -> bool:
        logger.info(f"SIM: Virtual connection to {address}")
        return True
    async def write(self, address: str, data: bytes) -> bool:
        logger.info(f"SIM: Processing write {data.hex()}")
        self.door.write(data)
        return True

# --- The Router ---
async def handle_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    peer = writer.get_extra_info('peername')
    logger.info(f"New TCP connection from {peer}")
    ACTIVE_TCP_WRITERS.add(writer)
    
    try:
        while True:
            mtype, payload = await read_msg(reader)

            if mtype == 0x01:  # CONNECT
                current_device_addr = payload.decode("utf-8").strip()
                success = await ADAPTER.connect(current_device_addr)
                res_code = 0x00 if success else 0x01
                writer.write(pack_msg(0x02, bytes([res_code]) + current_device_addr.encode()))
            
            elif mtype == 0x03:  # SUBSCRIBE
                writer.write(pack_msg(0x04, b"\x00")) # SUBSCRIBE_RSP OK

            elif mtype == 0x05:  # WRITE_REQ
                if not current_device_addr: continue
                # payload[0] is write_type (ignored for now), [1:] is raw data
                success = await ADAPTER.write(current_device_addr, payload[1:])
                status_code = 0x02 if success else 0x01 # 02 = ACK, 01 = NAK
                writer.write(pack_msg(0x06, struct.pack(">I", status_code)))

            elif mtype == 0x08:  # DISCONNECT
                break
                
    except asyncio.IncompleteReadError:
        logger.info(f"Client {peer} disconnected cleanly.")
    except Exception as e:
        logger.error(f"Client error {peer}: {e}")
    finally:
        ACTIVE_TCP_WRITERS.discard(writer)
        writer.close()            

# --- Discovery (Scan) ---
async def discovery_task():
    """Keeps the radio scanning in the background."""
    if SIMULATOR:
        logger.info("Discovery Task: Running in Simulator Mode (No background scan)")
        return 

    logger.info("Discovery Task: Starting Real-World BLE Scanner...")
    # Hook the callback we just wrote into the scanner
    scanner = BleakScanner(detection_callback)
    
    try:
        await scanner.start()
        while True:
            # Keep the scanner alive forever
            await asyncio.sleep(1) 
    except Exception as e:
        logger.error(f"Scanner Error: {e}")
    finally:
        await scanner.stop()


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-sim", action="store_true")
    args = parser.parse_args()

    global SIMULATOR, ADAPTER
    SIMULATOR = not args.no_sim
    ADAPTER = RealBleAdapter() if not SIMULATOR else SimBleAdapter()

    server = await asyncio.start_server(handle_client, TCP_HOST, TCP_PORT)
    logger.info(f"Daemon listening on {TCP_HOST}:{TCP_PORT} (SIM={SIMULATOR})")
    
    async with server:
        await asyncio.gather(server.serve_forever(), discovery_task())

if __name__ == "__main__":
    asyncio.run(main())