#!/usr/bin/env python3
"""
ble_daemon.py

Persistent BLE daemon with a TCP socket BLE façade and built-in simulator mode.

Protocol (length-prefixed):
  [4-byte BE length][1-byte msg_type][payload...]

Message types:
  0x01 CONNECT        Client -> Daemon (payload: UTF-8 selector)
  0x02 CONNECT_RSP    Daemon -> Client (0x00=OK|0x01=ERR + macOS-UUID UTF-8 if OK)
  0x03 SUBSCRIBE      Client -> Daemon (payload: notify characteristic UUID UTF-8)
  0x04 SUBSCRIBE_RSP  Daemon -> Client (0x00=OK|0x01=ERR)
  0x05 WRITE_REQ      Client -> Daemon (1 byte writeType + raw bytes)
  0x06 WRITE_RSP      Daemon -> Client (raw ACK bytes, e.g., 02 00 00 00)
  0x07 NOTIFY         Daemon -> Client (raw notification bytes)
  0x08 DISCONNECT     Client -> Daemon (no payload)
  0xFF ERROR          Either direction (1 byte code + UTF-8 message)

Simulator behavior (default):
  - On WRITE_REQ: immediately return WRITE_RSP (02 00 00 00).
  - If payload starts with 0x57, schedule NOTIFY OPENING after PROCESSING_DELAY,
    then NOTIFY OPEN after MOTOR_RUN_TIME.
Admin HTTP:
  - POST /admin/set  { state, processing_delay, motor_run_time }
  - GET  /admin/log  returns recent writes
"""
import asyncio
import struct
import logging
import time
from typing import Set, Tuple
from aiohttp import web

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ble_daemon")

# Configuration (defaults)
TCP_HOST = "127.0.0.1"
TCP_PORT = 9000
ADMIN_HTTP_HOST = "127.0.0.1"
ADMIN_HTTP_PORT = 9001

SIMULATOR = True  # default; run with --no-sim to enable real BLE (TODO)
PROCESSING_DELAY = 0.12  # seconds between ACK and first NOTIFY
MOTOR_RUN_TIME = 1.5     # seconds from OPENING -> OPEN

# Example status frame template (toy example). Replace with real frames if available.
STATUS_FRAME = bytes.fromhex("570f31000000050100020000010000e3")

# Device state and subscribers
class DeviceState:
    def __init__(self):
        self.state = "CLOSED"  # CLOSED, OPENING, OPEN, JAMMED
        self.battery = 100
        self.event_counter = 0
        self.subscribers: Set[Tuple[asyncio.StreamWriter, str]] = set()
        self.log = []  # list of (timestamp, hex_payload)

    def record_write(self, raw: bytes):
        self.log.append({"ts": time.time(), "hex": raw.hex()})
        # keep log bounded
        if len(self.log) > 1000:
            self.log = self.log[-1000:]

device = DeviceState()

# Framing helpers
def pack_msg(mtype: int, payload: bytes = b"") -> bytes:
    data = bytes([mtype]) + payload
    return struct.pack(">I", len(data)) + data

async def read_exact(reader: asyncio.StreamReader, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = await reader.read(n - len(buf))
        if not chunk:
            raise ConnectionError("EOF")
        buf += chunk
    return buf

# TCP client handler
async def handle_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    peer = writer.get_extra_info("peername")
    logger.info("Client connected: %s", peer)
    try:
        while True:
            hdr = await read_exact(reader, 4)
            length = struct.unpack(">I", hdr)[0]
            body = await read_exact(reader, length)
            mtype = body[0]
            payload = body[1:]
            await route_message(mtype, payload, reader, writer)
    except (ConnectionError, asyncio.IncompleteReadError):
        logger.info("Client disconnected: %s", peer)
    finally:
        # cleanup any subscriptions for this writer
        device.subscribers = {s for s in device.subscribers if s[0] is not writer}
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass

async def route_message(mtype: int, payload: bytes, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    if mtype == 0x01:  # CONNECT
        selector = payload.decode("utf-8", errors="ignore")
        logger.info("CONNECT selector=%s", selector)
        # In simulator, return a fake macOS UUID; in real mode, daemon would resolve actual UUID
        mac_uuid = "SIM-DEVICE-UUID-0001"
        writer.write(pack_msg(0x02, bytes([0x00]) + mac_uuid.encode()))
        await writer.drain()

    elif mtype == 0x03:  # SUBSCRIBE
        char_uuid = payload.decode("utf-8", errors="ignore")
        logger.info("SUBSCRIBE char=%s", char_uuid)
        device.subscribers.add((writer, char_uuid))
        writer.write(pack_msg(0x04, bytes([0x00])))
        await writer.drain()

    elif mtype == 0x05:  # WRITE_REQ
        if len(payload) < 1:
            writer.write(pack_msg(0xFF, bytes([1]) + b"Bad write payload"))
            await writer.drain()
            return
        write_type = payload[0]
        raw = payload[1:]
        logger.info("WRITE_REQ type=%d raw=%s", write_type, raw.hex())
        device.record_write(raw)
        # Immediate ATT write response (4-byte example)
        ack = bytes.fromhex("02000000")
        writer.write(pack_msg(0x06, ack))
        await writer.drain()
        # Simulator behavior: if payload looks like SwitchBot frame (starts with 0x57), schedule notifies
        valid = raw.startswith(b'\x57')
        if SIMULATOR and valid:
            asyncio.create_task(simulator_process_write(raw))
        else:
            logger.debug("Payload invalid or not in simulator mode; no notify scheduled")

    elif mtype == 0x08:  # DISCONNECT
        logger.info("Client requested disconnect")
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass

    else:
        logger.warning("Unknown message type: 0x%02x", mtype)
        writer.write(pack_msg(0xFF, bytes([2]) + b"Unknown message"))
        await writer.drain()

# Simulator processing: schedule OPENING then OPEN notifications
async def simulator_process_write(raw: bytes):
    opening_frame = build_status_frame("OPENING")
    await asyncio.sleep(PROCESSING_DELAY)
    await broadcast_notify(opening_frame)
    # simulate motor run
    await asyncio.sleep(MOTOR_RUN_TIME)
    device.state = "OPEN"
    device.event_counter += 1
    open_frame = build_status_frame("OPEN")
    await broadcast_notify(open_frame)

def build_status_frame(state: str) -> bytes:
    # Simple deterministic modification of STATUS_FRAME for tests.
    # Real implementation should construct according to SwitchBot spec.
    base = bytearray(STATUS_FRAME)
    # encode state in second-to-last byte as a toy example
    if state == "OPENING":
        base[-2] = 0x01
    elif state == "OPEN":
        base[-2] = 0x02
    elif state == "JAMMED":
        base[-2] = 0x03
    else:
        base[-2] = 0x00
    return bytes(base)

async def broadcast_notify(payload: bytes):
    logger.info("Broadcast NOTIFY %s to %d subscribers", payload.hex(), len(device.subscribers))
    for (w, char) in list(device.subscribers):
        try:
            w.write(pack_msg(0x07, payload))
            await w.drain()
        except Exception:
            device.subscribers.discard((w, char))

# Admin HTTP handlers
async def admin_set_state(request):
    data = await request.json()
    state = data.get("state")
    if state:
        device.state = state
    delay = data.get("processing_delay")
    if delay is not None:
        global PROCESSING_DELAY
        PROCESSING_DELAY = float(delay)
    motor = data.get("motor_run_time")
    if motor is not None:
        global MOTOR_RUN_TIME
        MOTOR_RUN_TIME = float(motor)
    return web.json_response({"ok": True, "state": device.state, "processing_delay": PROCESSING_DELAY, "motor_run_time": MOTOR_RUN_TIME})

async def admin_get_log(request):
    return web.json_response({"log": device.log[-200:]})

async def admin_health(request):
    return web.json_response({"ok": True, "simulator": SIMULATOR, "state": device.state})

# Entrypoint: start TCP server and admin HTTP
async def start_servers():
    server = await asyncio.start_server(handle_client, TCP_HOST, TCP_PORT)
    logger.info("TCP server listening on %s:%d", TCP_HOST, TCP_PORT)

    app = web.Application()
    app.add_routes([
        web.post("/admin/set", admin_set_state),
        web.get("/admin/log", admin_get_log),
        web.get("/admin/health", admin_health),
    ])
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, ADMIN_HTTP_HOST, ADMIN_HTTP_PORT)
    await site.start()
    logger.info("Admin HTTP listening on %s:%d", ADMIN_HTTP_HOST, ADMIN_HTTP_PORT)

    async with server:
        await server.serve_forever()

def main():
    # Declare globals before using them in argparse defaults to avoid SyntaxError
    global SIMULATOR, TCP_HOST, TCP_PORT, ADMIN_HTTP_PORT

    import argparse
    parser = argparse.ArgumentParser(description="BLE daemon with socket façade and simulator")
    parser.add_argument("--no-sim", action="store_true", help="Disable simulator and use real BLE (TODO)")
    parser.add_argument("--host", default=TCP_HOST, help="TCP host")
    parser.add_argument("--port", type=int, default=TCP_PORT, help="TCP port")
    parser.add_argument("--admin-port", type=int, default=ADMIN_HTTP_PORT, help="Admin HTTP port")
    args = parser.parse_args()

    if args.no_sim:
        SIMULATOR = False
    TCP_HOST = args.host
    TCP_PORT = args.port
    ADMIN_HTTP_PORT = args.admin_port

    logger.info("Starting ble_daemon (simulator=%s) on %s:%d (admin %d)", SIMULATOR, TCP_HOST, TCP_PORT, ADMIN_HTTP_PORT)
    try:
        asyncio.run(start_servers())
    except KeyboardInterrupt:
        logger.info("Shutting down")

if __name__ == "__main__":
    main()
