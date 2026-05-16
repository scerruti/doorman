import asyncio
import struct
import argparse
import logging
import os
import configparser
from bleak import BleakScanner, BleakClient
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

# SwitchBot Service and Characteristics
SERVICE_UUID = "cba20d00-224d-11e6-9fb8-0002a5d5c51b"
RX_CHAR_UUID = "cba20002-224d-11e6-9fb8-0002a5d5c51b"
TX_CHAR_UUID = "cba20003-224d-11e6-9fb8-0002a5d5c51b"

# TCP Message Types (Wire Protocol)
MSG_TYPE_WRITE = 0x01
MSG_TYPE_NOTIFY = 0x02
MSG_TYPE_SCAN = 0x03
MSG_TYPE_CONNECT = 0x04
MSG_TYPE_DISCONNECT = 0x05

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("ble_daemon")

def load_creds(file_path):
    if not os.path.exists(file_path):
        return None
    config = configparser.ConfigParser()
    with open(file_path, 'r') as f:
        config.read_string('[DEFAULT]\n' + f.read())
    try:
        key_hex = config.get('DEFAULT', 'switchbot.device.encryption.key', fallback="00"*16).strip()
        if not key_hex: key_hex = "00"*16
        key_id_hex = config.get('DEFAULT', 'switchbot.device.key.id', fallback="51").strip()
        if not key_id_hex: key_id_hex = "51"
        mac_address = config.get('DEFAULT', 'switchbot.mac.address', fallback="AA:BB:CC:DD:EE:FF").strip()
        if not mac_address: mac_address = "AA:BB:CC:DD:EE:FF"
        return {
            "key": bytes.fromhex(key_hex),
            "key_id": bytes.fromhex(key_id_hex),
            "mac": mac_address
        }
    except Exception as e:
        logger.error(f"Error parsing {file_path}: {e}")
        return None

class DumbDaemon:
    def __init__(self, host='127.0.0.1', port=9000, sim_mode=False):
        self.host = host
        self.port = port
        self.sim_mode = sim_mode
        self.clients = set()
        self.scanner = None
        self.active_client = None
        self.mac_mapping = {}
        
        # Simulator state
        self.sim_door_open = False
        self.creds = load_creds('device-secrets.properties') if sim_mode else None
        self.sim_mac = self.creds['mac'] if self.creds else "AA:BB:CC:DD:EE:FF"

    async def start(self):
        server = await asyncio.start_server(self.handle_client, self.host, self.port)
        logger.info(f"Dumb Relay Daemon listening on {self.host}:{self.port} (Sim Mode: {self.sim_mode})")
        
        if self.sim_mode:
            asyncio.create_task(self.sim_scan_loop())
        else:
            self.scanner = BleakScanner(self.detection_callback)
            await self.scanner.start()

        async with server:
            await server.serve_forever()

    def broadcast_tcp(self, data: bytes):
        # Length prefixed buffer
        packet = struct.pack('>I', len(data)) + data
        for w in self.clients:
            try:
                w.write(packet)
            except Exception as e:
                logger.error(f"Failed to write to client: {e}")

    async def handle_client(self, reader, writer):
        self.clients.add(writer)
        logger.info("Kotlin Controller connected via TCP")
        try:
            while True:
                length_bytes = await reader.readexactly(4)
                length = struct.unpack('>I', length_bytes)[0]
                data = await reader.readexactly(length)
                
                if not data:
                    break
                    
                msg_type = data[0]
                payload = data[1:]
                
                if msg_type == MSG_TYPE_WRITE:
                    logger.info(f"Received WRITE (Type 0x01) -> {payload.hex()}")
                    await self.handle_write(payload)
                elif msg_type == MSG_TYPE_CONNECT:
                    mac = payload.decode('utf-8')
                    logger.info(f"Received CONNECT (Type 0x04) -> {mac}")
                    await self.handle_connect(mac)
                elif msg_type == MSG_TYPE_DISCONNECT:
                    logger.info("Received DISCONNECT (Type 0x05)")
                    await self.handle_disconnect()
                    
        except asyncio.IncompleteReadError:
            pass
        except Exception as e:
            logger.error(f"TCP client error: {e}")
        finally:
            logger.info("Kotlin Controller disconnected")
            self.clients.remove(writer)
            writer.close()

    def detection_callback(self, device, advertisement_data):
        # SwitchBots often omit the massive 128-bit Service UUID to save packet space, 
        # so we also check for their specific Manufacturer ID (0x0969 / 2409)
        is_switchbot = False
        if SERVICE_UUID.lower() in [str(u).lower() for u in advertisement_data.service_uuids]:
            is_switchbot = True
        elif 0x0969 in advertisement_data.manufacturer_data:
            is_switchbot = True

        if is_switchbot:
            mfr_data = advertisement_data.manufacturer_data
            if mfr_data:
                # Iterate Manufacturer Data blocks (usually 0x0969 for SwitchBot)
                for mfr_id, mfr_bytes in mfr_data.items():
                    mfr_bytes_obj = bytes(mfr_bytes)
                    reported_mac = device.address
                    
                    # SwitchBot encodes the real MAC in the first 6 bytes of the Manufacturer Data
                    if len(mfr_bytes_obj) >= 6:
                        real_mac = ":".join(f"{b:02X}" for b in mfr_bytes_obj[:6])
                        reported_mac = real_mac
                        
                    # Map the reported MAC back to the OS-specific device handle
                    self.mac_mapping[reported_mac] = device.address
                    
                    self.send_scan_packet(reported_mac, advertisement_data.rssi, device.name, mfr_bytes_obj)

    async def handle_connect(self, mac):
        if self.sim_mode:
            logger.info(f"[SIM] Connected to {mac}")
            return

        if self.active_client and self.active_client.is_connected:
            await self.active_client.disconnect()

        # Map the requested MAC (e.g. 3C:84...) back to the macOS UUID if needed
        target_address = self.mac_mapping.get(mac, mac)

        # macOS CoreBluetooth requires a BLEDevice handle rather than a raw MAC string
        logger.info(f"Resolving hardware handle for {target_address} (requested: {mac})...")
        device = await BleakScanner.find_device_by_address(target_address, timeout=10.0)
        if not device:
            logger.error(f"Failed to connect: Device {target_address} not found in range.")
            return

        self.active_client = BleakClient(device)
        try:
            await self.active_client.connect()
            logger.info(f"Connected to BLE device {mac}")
            await self.active_client.start_notify(TX_CHAR_UUID, self.notify_callback)
        except Exception as e:
            logger.error(f"Failed to connect to {mac}: {e}")

    async def handle_disconnect(self):
        if self.sim_mode:
            logger.info("[SIM] Disconnected")
            return
        if self.active_client and self.active_client.is_connected:
            await self.active_client.disconnect()
            logger.info("Disconnected from BLE device")

    async def handle_write(self, data):
        if self.sim_mode:
            logger.info(f"[SIM] Encrypted payload received: {data.hex()}")
            if self.creds:
                if len(data) != 17:
                    logger.warning(f"[SIM] Reject: Expected 17 bytes, got {len(data)}")
                    return
                
                key_id = data[0:1]
                if key_id != self.creds['key_id']:
                    logger.warning(f"[SIM] Reject: Expected Key ID {self.creds['key_id'].hex()}, got {key_id.hex()}")
                    return
                    
                iv = key_id + b'\x00' * 15
                cipher = Cipher(algorithms.AES(self.creds['key']), modes.CTR(iv))
                decryptor = cipher.decryptor()
                plaintext = decryptor.update(data[1:]) + decryptor.finalize()
                
                logger.info(f"[SIM] Decrypted plain payload: {plaintext.hex()}")
                
                if plaintext[1:4] == bytes([0x57, 0x01, 0x01]):
                    logger.info(f"[SIM] Valid TOGGLE command (Counter: {plaintext[0]}). Motor activated.")
                    self.broadcast_tcp(bytes([MSG_TYPE_NOTIFY, 0x01]))
                    asyncio.create_task(self.simulate_physical_movement())
                else:
                    logger.warning(f"[SIM] Reject: Unknown command {plaintext[1:4].hex()}")
            else:
                logger.warning("[SIM] No credentials loaded. Rejecting payload.")
            return

        if self.active_client and self.active_client.is_connected:
            try:
                await self.active_client.write_gatt_char(RX_CHAR_UUID, data, response=False)
                logger.info("Raw encrypted payload written to radio")
            except Exception as e:
                logger.error(f"Write failed: {e}")

    async def simulate_physical_movement(self):
        logger.info("[SIM] Door is in motion...")
        if not self.sim_door_open:
            # Opening: The magnet separates almost immediately when the door lifts
            await asyncio.sleep(1.0)
            self.sim_door_open = True
            logger.info("[SIM] Magnet separated. Sensor reading is now OPEN.")
        else:
            # Closing: The magnet doesn't touch until the door hits the floor
            await asyncio.sleep(15.0)
            self.sim_door_open = False
            logger.info("[SIM] Door reached floor. Magnet touching. Sensor reading is now CLOSED.")

    def notify_callback(self, sender, data):
        logger.info(f"Notification from radio: {data.hex()}")
        packet = bytearray([MSG_TYPE_NOTIFY])
        packet.extend(data)
        self.broadcast_tcp(bytes(packet))

    async def sim_scan_loop(self):
        while True:
            # Emit a dummy 13-byte manufacturer data array
            mfr_bytes = bytearray(13)
            # Apply the 0x43 / 0x46 status toggle to index 6
            mfr_bytes[6] = 0x46 if self.sim_door_open else 0x43
            
            self.send_scan_packet(self.sim_mac, -50, "SwitchBot-Sim", mfr_bytes)
            await asyncio.sleep(1.0)

    def send_scan_packet(self, mac: str, rssi: int, name: str, mfr_bytes: bytes):
        mac_bytes = mac.encode('utf-8')
        name_bytes = (name or "Unknown").encode('utf-8')
        
        packet = bytearray()
        packet.append(MSG_TYPE_SCAN) # 0x03
        packet.append(len(mac_bytes))
        packet.extend(mac_bytes)
        # Safely pack the RSSI as a signed 1-byte integer
        packet.extend(max(-128, min(127, rssi)).to_bytes(1, byteorder='big', signed=True))
        packet.append(len(name_bytes))
        packet.extend(name_bytes)
        packet.append(len(mfr_bytes))
        packet.extend(mfr_bytes)
        
        self.broadcast_tcp(bytes(packet))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--sim", action="store_true", help="Run as a simulated SwitchBot device")
    args = parser.parse_args()
    
    daemon = DumbDaemon(sim_mode=args.sim)
    try:
        asyncio.run(daemon.start())
    except KeyboardInterrupt:
        logger.info("Daemon stopped.")