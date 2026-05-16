#!/usr/bin/env python3
"""
ble_daemon.py — DumbDaemon: TCP bridge between Kotlin BLE clients and real or simulated hardware.

Framing (both directions):
    [4 bytes big-endian length][1 byte type][N bytes payload]

Kotlin → Daemon:
    0x01  WRITE      payload = raw characteristic bytes
    0x04  CONNECT    payload = MAC address (UTF-8)
    0x05  DISCONNECT (no payload)

Daemon → Kotlin:
    0x02  NOTIFY     payload = raw BLE notification bytes
    0x03  SCAN       payload = [mac_len][mac][rssi_signed][name_len][name][mfr_len][mfr_bytes]
"""
import asyncio
import struct
import argparse
import logging
import os
import configparser
from Crypto.Cipher import AES
from Crypto.Util import Counter
from bleak import BleakScanner, BleakClient
from simulated_door import SimulatedDoor
import opener_protocol

SERVICE_UUID = "cba20d00-224d-11e6-9fb8-0002a5d5c51b"
RX_CHAR_UUID = opener_protocol.WRITE_UUID
TX_CHAR_UUID = opener_protocol.NOTIFY_UUID

MSG_TYPE_WRITE      = 0x01
MSG_TYPE_NOTIFY     = 0x02
MSG_TYPE_SCAN       = 0x03
MSG_TYPE_CONNECT    = 0x04
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
        mac     = config.get('DEFAULT', 'switchbot.mac.address',           fallback='AA:BB:CC:DD:EE:FF').strip()
        key_hex = config.get('DEFAULT', 'switchbot.device.encryption.key', fallback='').strip()
        key_id  = config.get('DEFAULT', 'switchbot.device.key.id',         fallback='').strip()
        return {
            'mac':     mac or 'AA:BB:CC:DD:EE:FF',
            'key_hex': key_hex,
            'key_id':  int(key_id, 16) if key_id else 0,
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
        # Maps real MAC → macOS UUID so we can pass the right handle to Bleak on connect
        self.mac_mapping = {}

        creds = load_creds('device-secrets.properties')
        sim_mac     = creds['mac']     if creds else 'AA:BB:CC:DD:EE:FF'
        key_hex     = creds['key_hex'] if creds else ''
        self._key    = bytes.fromhex(key_hex) if key_hex else None
        self._key_id = creds['key_id'] if creds else 0

        if sim_mode:
            self.sim_door = SimulatedDoor(
                address=sim_mac,
                name='SwitchBot-Sim',
                rssi=-50,
            )
            logger.info(f"Simulator MAC: {sim_mac}")

    # -------------------------------------------------------------------------
    # Server lifecycle
    # -------------------------------------------------------------------------

    async def start(self):
        server = await asyncio.start_server(self.handle_client, self.host, self.port)
        logger.info(f"DumbDaemon listening on {self.host}:{self.port}  sim={self.sim_mode}")

        if self.sim_mode:
            asyncio.create_task(self.sim_scan_loop())
            asyncio.create_task(self.sim_heartbeat_loop())
        else:
            self.scanner = BleakScanner(self.detection_callback)
            await self.scanner.start()

        async with server:
            await server.serve_forever()

    # -------------------------------------------------------------------------
    # TCP transport
    # -------------------------------------------------------------------------

    def broadcast_tcp(self, data: bytes):
        packet = struct.pack('>I', len(data)) + data
        for w in list(self.clients):
            try:
                w.write(packet)
            except Exception as e:
                logger.error(f"broadcast_tcp failed: {e}")

    async def handle_client(self, reader, writer):
        self.clients.add(writer)
        logger.info("Kotlin client connected via TCP")
        try:
            while True:
                length_bytes = await reader.readexactly(4)
                length = struct.unpack('>I', length_bytes)[0]
                data = await reader.readexactly(length)
                if not data:
                    break

                msg_type = data[0]
                payload  = data[1:]

                if msg_type == MSG_TYPE_WRITE:
                    logger.info(f"WRITE → {payload.hex()}")
                    await self.handle_write(payload)
                elif msg_type == MSG_TYPE_CONNECT:
                    mac = payload.decode('utf-8')
                    logger.info(f"CONNECT → {mac}")
                    await self.handle_connect(mac)
                elif msg_type == MSG_TYPE_DISCONNECT:
                    logger.info("DISCONNECT")
                    await self.handle_disconnect()

        except asyncio.IncompleteReadError:
            pass
        except Exception as e:
            logger.error(f"TCP client error: {e}")
        finally:
            logger.info("Kotlin client disconnected")
            self.clients.discard(writer)
            writer.close()

    def _decrypt(self, data: bytes) -> bytes:
        """AES-CTR decrypt matching AesCtr.kt: first byte is the IV byte, rest is ciphertext."""
        if self._key is None or len(data) < 2:
            return data
        iv = bytes([data[0]]) + bytes(15)
        ctr = Counter.new(128, initial_value=int.from_bytes(iv, 'big'))
        return AES.new(self._key, AES.MODE_CTR, counter=ctr).decrypt(data[1:])

    def _encrypt(self, data: bytes) -> bytes:
        """AES-CTR encrypt matching AesCtr.kt: prepend keyId byte as IV, then ciphertext."""
        if self._key is None:
            return data
        iv = bytes([self._key_id]) + bytes(15)
        ctr = Counter.new(128, initial_value=int.from_bytes(iv, 'big'))
        return bytes([self._key_id]) + AES.new(self._key, AES.MODE_CTR, counter=ctr).encrypt(data)

    # -------------------------------------------------------------------------
    # Real BLE — scanner callback (with macOS UUID → real MAC substitution)
    # -------------------------------------------------------------------------

    def detection_callback(self, device, advertisement_data):
        is_switchbot = (
            SERVICE_UUID.lower() in [str(u).lower() for u in advertisement_data.service_uuids]
            or 0x0969 in advertisement_data.manufacturer_data
        )
        if not is_switchbot:
            return

        mfr_data = advertisement_data.manufacturer_data
        for mfr_id, mfr_bytes in mfr_data.items():
            mfr_bytes = bytes(mfr_bytes)
            reported_mac = device.address

            # macOS hides the real MAC behind a per-boot UUID.
            # SwitchBot embeds the real MAC as the first 6 bytes of company-ID 0x0969 data.
            if mfr_id == 0x0969 and len(mfr_bytes) >= 6:
                real_mac = ':'.join(f'{b:02X}' for b in mfr_bytes[:6])
                reported_mac = real_mac
                if reported_mac not in self.mac_mapping:
                    logger.info(f"GDO: {device.name} uuid={device.address} → mac={real_mac} RSSI={advertisement_data.rssi}")

            # Keep reverse mapping so handle_connect can pass the UUID back to Bleak
            self.mac_mapping[reported_mac] = device.address

            self.send_scan_packet(reported_mac, advertisement_data.rssi, device.name, mfr_bytes)

    # -------------------------------------------------------------------------
    # Real BLE — connect / disconnect / write / notify
    # -------------------------------------------------------------------------

    async def handle_connect(self, mac):
        if self.sim_mode:
            logger.info(f"[SIM] Connected to {mac}")
            return

        if self.active_client and self.active_client.is_connected:
            await self.active_client.disconnect()

        # Resolve back to macOS UUID handle (required by Bleak on macOS)
        target = self.mac_mapping.get(mac, mac)
        logger.info(f"Resolving {target} (requested {mac})…")
        device = await BleakScanner.find_device_by_address(target, timeout=10.0)
        if not device:
            logger.error(f"Device {target} not found")
            return

        self.active_client = BleakClient(device)
        try:
            await self.active_client.connect()
            logger.info(f"Connected to {mac}")
            await self.active_client.start_notify(TX_CHAR_UUID, self.notify_callback)
        except Exception as e:
            logger.error(f"Connect failed: {e}")

    async def handle_disconnect(self):
        if self.sim_mode:
            logger.info("[SIM] Disconnected")
            return
        if self.active_client and self.active_client.is_connected:
            await self.active_client.disconnect()
            logger.info("Disconnected from BLE device")

    async def handle_write(self, data):
        if self.sim_mode:
            plaintext = self._decrypt(data)
            logger.info(f"[SIM] Write encrypted={data.hex()} plain={plaintext.hex()}")
            self.sim_door.write(plaintext)
            for payload in self.sim_door.get_pending_notifications():
                encrypted = self._encrypt(payload)
                logger.info(f"[SIM] Notification plain={payload.hex()} encrypted={encrypted.hex()}")
                self.broadcast_tcp(bytes([MSG_TYPE_NOTIFY]) + encrypted)
            return

        if self.active_client and self.active_client.is_connected:
            try:
                await self.active_client.write_gatt_char(RX_CHAR_UUID, data, response=False)
                logger.info("Write OK")
            except Exception as e:
                logger.error(f"Write failed: {e}")

    def notify_callback(self, sender, data):
        logger.info(f"BLE notify: {bytes(data).hex()}")
        self.broadcast_tcp(bytes([MSG_TYPE_NOTIFY]) + bytes(data))

    # -------------------------------------------------------------------------
    # Simulator loops
    # -------------------------------------------------------------------------

    async def sim_scan_loop(self):
        """Broadcast periodic scan advertisements for the simulated device."""
        while True:
            door = self.sim_door
            mfr_bytes = bytearray(13)
            # Bytes 0-5: MAC address (matches real hardware layout)
            for i, part in enumerate(door.address.split(':')):
                mfr_bytes[i] = int(part, 16)
            # Byte 6 LSB: door state — 0 = OPEN, 1 = CLOSED (matches real device)
            is_open = door.get_state() in ('OPEN', 'CLOSING')
            mfr_bytes[6] = 0x00 if is_open else 0x01
            self.send_scan_packet(door.address, door.rssi, door.name, bytes(mfr_bytes))
            await asyncio.sleep(1.0)

    async def sim_heartbeat_loop(self):
        """Advance simulated door time and flush transition notifications."""
        import time
        while True:
            self.sim_door.tick(time.time())
            for payload in self.sim_door.get_pending_notifications():
                encrypted = self._encrypt(payload)
                logger.info(f"[SIM] Heartbeat notify plain={payload.hex()} encrypted={encrypted.hex()}")
                self.broadcast_tcp(bytes([MSG_TYPE_NOTIFY]) + encrypted)
            await asyncio.sleep(0.5)

    # -------------------------------------------------------------------------
    # Scan packet encoder (binary 0x03 format)
    # -------------------------------------------------------------------------

    def send_scan_packet(self, mac: str, rssi: int, name: str, mfr_bytes: bytes):
        mac_b   = mac.encode('utf-8')
        name_b  = (name or '').encode('utf-8')
        rssi_b  = max(-128, min(127, rssi)).to_bytes(1, byteorder='big', signed=True)

        packet = bytearray([MSG_TYPE_SCAN])
        packet.append(len(mac_b));  packet.extend(mac_b)
        packet.extend(rssi_b)
        packet.append(len(name_b)); packet.extend(name_b)
        packet.append(len(mfr_bytes)); packet.extend(mfr_bytes)

        self.broadcast_tcp(bytes(packet))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Doorman BLE daemon')
    parser.add_argument('--sim', action='store_true', help='Use simulated door (default: real hardware)')
    args = parser.parse_args()

    daemon = DumbDaemon(sim_mode=args.sim)
    try:
        asyncio.run(daemon.start())
    except KeyboardInterrupt:
        logger.info("Daemon stopped.")
