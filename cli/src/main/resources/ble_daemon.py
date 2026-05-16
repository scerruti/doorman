import asyncio
import random
import struct
import argparse
import logging
from bleak import BleakScanner, BleakClient

# SwitchBot Service and Characteristics (classic UART)
SERVICE_UUID = "cba20d00-224d-11e6-9fb8-0002a5d5c51b"
RX_CHAR_UUID = "cba20002-224d-11e6-9fb8-0002a5d5c51b"  # Write
TX_CHAR_UUID = "cba20003-224d-11e6-9fb8-0002a5d5c51b"  # Notify

# TCP Message Types (Wire Protocol)
MSG_TYPE_WRITE = 0x01
MSG_TYPE_NOTIFY = 0x02
MSG_TYPE_SCAN = 0x03
MSG_TYPE_CONNECT = 0x04
MSG_TYPE_DISCONNECT = 0x05

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("ble_daemon")


class DumbDaemon:
    """
    TCP <-> BLE bridge for a SwitchBot Relay-style device using the classic
    unencrypted UART protocol.

    - Over TCP:
        * Client sends:
            - 0x01 <payload>      => WRITE to RX_CHAR_UUID
            - 0x04 <mac-string>   => CONNECT to device
            - 0x05                => DISCONNECT
        * Daemon sends:
            - 0x03 ...            => SCAN results
            - 0x02 <ble-data>     => NOTIFY from TX_CHAR_UUID

    - Over BLE:
        * Writes are raw SwitchBot UART frames (e.g. 57 01 01 5F for toggle).
        * Notifications are forwarded as-is.
    """

    def __init__(self, host="127.0.0.1", port=9000, sim_mode=False):
        self.host = host
        self.port = port
        self.sim_mode = sim_mode

        self.clients = set()
        self.scanner = None
        self.active_client: BleakClient | None = None
        self.mac_mapping: dict[str, str] = {}

        # Simulator state
        self.sim_door_open = False
        # Give the simulated device a stable fake MAC
        self.sim_mac = "AA:BB:CC:DD:EE:FF"

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

    # -------------------------------------------------------------------------
    # TCP side
    # -------------------------------------------------------------------------

    def broadcast_tcp(self, data: bytes):
        # Length-prefixed buffer
        packet = struct.pack(">I", len(data)) + data
        dead = []
        for w in self.clients:
            try:
                w.write(packet)
            except Exception as e:
                logger.error(f"Failed to write to client: {e}")
                dead.append(w)
        for w in dead:
            self.clients.discard(w)

    async def handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        self.clients.add(writer)
        logger.info("Kotlin Controller connected via TCP")
        try:
            while True:
                length_bytes = await reader.readexactly(4)
                length = struct.unpack(">I", length_bytes)[0]
                data = await reader.readexactly(length)
                if not data:
                    break

                msg_type = data[0]
                payload = data[1:]

                if msg_type == MSG_TYPE_WRITE:
                    logger.info(f"Received WRITE (0x01) -> {payload.hex()}")
                    await self.handle_write(payload)
                elif msg_type == MSG_TYPE_CONNECT:
                    mac = payload.decode("utf-8")
                    logger.info(f"Received CONNECT (0x04) -> {mac}")
                    await self.handle_connect(mac)
                elif msg_type == MSG_TYPE_DISCONNECT:
                    logger.info("Received DISCONNECT (0x05)")
                    await self.handle_disconnect()
        except asyncio.IncompleteReadError:
            pass
        except Exception as e:
            logger.error(f"TCP client error: {e}")
        finally:
            logger.info("Kotlin Controller disconnected")
            self.clients.discard(writer)
            writer.close()

    # -------------------------------------------------------------------------
    # BLE scanning and mapping
    # -------------------------------------------------------------------------

    def detection_callback(self, device, advertisement_data):
        # Identify SwitchBot-like devices by service UUID or manufacturer ID 0x0969
        is_switchbot = False
        if SERVICE_UUID.lower() in [str(u).lower() for u in advertisement_data.service_uuids]:
            is_switchbot = True
        elif 0x0969 in advertisement_data.manufacturer_data:
            is_switchbot = True

        if not is_switchbot:
            return

        mfr_data = advertisement_data.manufacturer_data
        if mfr_data:
            for mfr_id, mfr_bytes in mfr_data.items():
                mfr_bytes_obj = bytes(mfr_bytes)
                reported_mac = device.address

                # Many SwitchBot devices encode the real MAC in the first 6 bytes
                if len(mfr_bytes_obj) >= 6:
                    real_mac = ":".join(f"{b:02X}" for b in mfr_bytes_obj[:6])
                    reported_mac = real_mac

                # Map the "real" MAC to the OS-specific address
                self.mac_mapping[reported_mac] = device.address

                self.send_scan_packet(
                    reported_mac,
                    advertisement_data.rssi,
                    device.name,
                    mfr_bytes_obj,
                )

    # -------------------------------------------------------------------------
    # BLE connect / disconnect / write / notify
    # -------------------------------------------------------------------------

    async def handle_connect(self, mac: str):
        if self.sim_mode:
            logger.info(f"[SIM] Connected to {mac}")
            return

        if self.active_client and self.active_client.is_connected:
            await self.active_client.disconnect()

        target_address = self.mac_mapping.get(mac, mac)
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
            self.active_client = None

    async def handle_disconnect(self):
        if self.sim_mode:
            logger.info("[SIM] Disconnected")
            return

        if self.active_client and self.active_client.is_connected:
            await self.active_client.disconnect()
            logger.info("Disconnected from BLE device")
        self.active_client = None

    async def handle_write(self, data: bytes):
        """
        For real hardware:
            - 'data' is a raw SwitchBot UART frame (e.g. 57 01 01 5F).
            - We write it directly to RX_CHAR_UUID with no encryption.

        For sim mode:
            - We interpret the frame and drive the simulated door state.
        """
        if self.sim_mode:
            await self.handle_write_sim(data)
            return

        if self.active_client and self.active_client.is_connected:
            try:
                await self.active_client.write_gatt_char(RX_CHAR_UUID, data, response=False)
                logger.info(f"Raw payload written to radio: {data.hex()}")
            except Exception as e:
                logger.error(f"Write failed: {e}")
        else:
            logger.warning("WRITE requested but no active BLE connection")

    def notify_callback(self, sender, data: bytes):
        logger.info(f"Notification from radio: {data.hex()}")
        packet = bytearray([MSG_TYPE_NOTIFY])
        packet.extend(data)
        self.broadcast_tcp(bytes(packet))

    # -------------------------------------------------------------------------
    # Simulation mode (no encryption, just fake a relay + door sensor)
    # -------------------------------------------------------------------------

    async def handle_write_sim(self, data: bytes):
        """
        Simulate a SwitchBot Relay-like device.

        We assume a simple "toggle" command is sent as a SwitchBot UART frame.
        For example, many devices use:
            57 01 01 XX
        where XX is a checksum.

        Here we just look for the 57 01 01 prefix and treat it as "toggle".
        """
        logger.info(f"[SIM] Payload received: {data.hex()}")

        if len(data) >= 3 and data[0] == 0x57 and data[1] == 0x01 and data[2] == 0x01:
            logger.info("[SIM] TOGGLE command detected. Motor activated.")
            # Immediately send a "relay activated" notification (fake format)
            # You can adjust this to match whatever your Kotlin side expects.
            notify = bytearray([MSG_TYPE_NOTIFY, 0x01])
            self.broadcast_tcp(bytes(notify))

            # Start door movement simulation
            asyncio.create_task(self.simulate_physical_movement())
        else:
            logger.warning(f"[SIM] Unknown command frame: {data.hex()}")

    async def simulate_physical_movement(self):
        logger.info("[SIM] Door is in motion...")
        if not self.sim_door_open:
            # Opening: magnet separates almost immediately
            await asyncio.sleep(1.0)
            self.sim_door_open = True
            logger.info("[SIM] Magnet separated. Sensor reading is now OPEN.")
        else:
            # Closing: magnet touches only when door hits the floor
            await asyncio.sleep(15.0)
            self.sim_door_open = False
            logger.info("[SIM] Door reached floor. Sensor reading is now CLOSED.")

    async def sim_scan_loop(self):
        """
        Periodically emit a fake scan result for the simulated device.

        We keep the manufacturer data arbitrary but stable enough that the
        Kotlin side can treat it like a real SwitchBot advertisement.
        """
        while True:
            mfr_bytes = bytearray(13)

            # Encode door state in one byte (arbitrary convention):
            # 0x9A = open, 0x9B = closed (matches your earlier pattern)
            mfr_bytes[6] = 0x9A if self.sim_door_open else 0x9B

            self.send_scan_packet(self.sim_mac, -50, "SwitchBot-Sim", mfr_bytes)
            await asyncio.sleep(1.0)

    # -------------------------------------------------------------------------
    # Scan packet -> TCP
    # -------------------------------------------------------------------------

    def send_scan_packet(self, mac: str, rssi: int, name: str, mfr_bytes: bytes):
        mac_bytes = mac.encode("utf-8")
        name_bytes = (name or "Unknown").encode("utf-8")

        packet = bytearray()
        packet.append(MSG_TYPE_SCAN)  # 0x03
        packet.append(len(mac_bytes))
        packet.extend(mac_bytes)

        # Pack RSSI as signed 1-byte integer
        packet.extend(max(-128, min(127, rssi)).to_bytes(1, byteorder="big", signed=True))

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
