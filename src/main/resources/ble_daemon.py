#!/usr/bin/env python3
"""
ble_daemon.py

Persistent BLE daemon with a TCP socket BLE façade and built-in simulator mode.

This is a drop-in replacement that:
 - Adds a simulator discovery task that periodically broadcasts synthetic discovery frames.
 - Adds deterministic emit_once helpers for unit tests.
 - Adds admin HTTP endpoints to inspect and control simulated devices and cadence.
 - Adds TODO markers for further improvements (deterministic RNG, payload compatibility, API purity).

Keep existing protocol and simulator WRITE_REQ behavior unchanged.
"""
import asyncio
import struct
import logging
import time
import random
import re
import uuid
from typing import Set, Tuple, List, Dict, Optional
from aiohttp import web

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ble_daemon")

# Requires: pip install bleak
try:
    from bleak import BleakScanner
    _BLEAK_AVAILABLE = True
except Exception:
    _BLEAK_AVAILABLE = False

# Robust helper to get RSSI from available places
def _get_rssi(device, advertisement_data):
    try:
        return int(getattr(device, "rssi"))
    except Exception:
        pass
    try:
        return int(getattr(advertisement_data, "rssi"))
    except Exception:
        pass
    try:
        return int(device.metadata.get("rssi"))
    except Exception:
        pass
    return 0

# Robust manufacturer data -> bytes (flatten to hex)
def _flatten_manufacturer_data(advertisement_data):
    try:
        mdata = getattr(advertisement_data, "manufacturer_data", {}) or {}
        parts = []
        for k, v in mdata.items():
            if isinstance(v, (bytes, bytearray)):
                parts.append(bytes(v))
            elif isinstance(v, int):
                parts.append(bytes([v]))
            elif isinstance(v, (list, tuple)):
                parts.append(bytes(v))
            else:
                parts.append(str(v).encode("utf-8"))
        return b"".join(parts)
    except Exception:
        return b""

# Build discovery payload (safe)
def build_discovery_frame(device, advertisement_data) -> bytes:
    addr = (device.address or "").encode("utf-8")
    name = (device.name or "").encode("utf-8")
    rssi_val = _get_rssi(device, advertisement_data)
    rssi_bytes = str(rssi_val).encode("utf-8")
    adv = _flatten_manufacturer_data(advertisement_data)
    payload = b"|".join([addr, rssi_bytes, name, adv.hex().encode("utf-8")])
    return payload

# Bleak discovery task (use the robust build_discovery_frame)
async def bleak_discovery_task():
    if not _BLEAK_AVAILABLE:
        logger.warning("Bleak not available; discovery disabled")
        return

    async def detection_callback(device, advertisement_data):
        try:
            rssi_val = _get_rssi(device, advertisement_data)
            logger.info("Bleak discovered: %s name=%s rssi=%s", device.address, device.name, rssi_val)
            payload = build_discovery_frame(device, advertisement_data)
            await broadcast_notify(payload)
        except Exception:
            logger.exception("Error in detection_callback")

    scanner = BleakScanner(detection_callback)
    try:
        await scanner.start()
        logger.info("Bleak scanner started")
        while True:
            await asyncio.sleep(60)
    except asyncio.CancelledError:
        try:
            await scanner.stop()
        except Exception:
            pass
        raise
    except Exception:
        logger.exception("Bleak scanner failed")


# Configuration (defaults)
TCP_HOST = "127.0.0.1"
TCP_PORT = 9000
ADMIN_HTTP_HOST = "127.0.0.1"
ADMIN_HTTP_PORT = 9001

SIMULATOR = True  # default; run with --no-sim to enable real BLE (TODO)
PROCESSING_DELAY = 0.12  # seconds between ACK and first NOTIFY
MOTOR_RUN_TIME = 1.5     # seconds from OPENING -> OPEN

# Simulator discovery configuration (new)
SIMULATOR_DISCOVERY_PERIOD_S = 0.2  # seconds between discovery broadcasts
SIMULATOR_RNG_SEED: Optional[int] = None  # TODO: set a seed for deterministic tests if desired

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
        if len(self.log) > 1000:
            self.log = self.log[-1000:]

device = DeviceState()

# --- Simulator device list (modifiable via admin endpoints) ------------------
# Each entry: { "address": str, "name": str, "base_rssi": int, "adv": str (hex), "jitter": int }
# Store adv as a hex string (service uuid compact + manufacturer prefix + 12-hex MAC + optional extra)
SIMULATED_DEVICES: List[Dict] = [
    {"address": "SIM:AA:BB:CC:01", "name": "SimLock1", "base_rssi": -74, "adv": "", "jitter": 2},
    {"address": "SIM:AA:BB:CC:02", "name": "SimLock2", "base_rssi": -80, "adv": "", "jitter": 2},
]
# TODO: Consider moving SIMULATED_DEVICES into a class with thread-safe access if modified concurrently.
# TODO: Consider exposing per-device spike behavior and schedules via admin API.

# Deterministic RNG for simulator (optional)
_sim_rng = random.Random(SIMULATOR_RNG_SEED)

MAC_RE = re.compile(r'^[0-9a-f]{12}$', re.IGNORECASE)

def make_locally_administered_mac() -> str:
    """Return a 12-hex-digit MAC string with the locally-administered bit set."""
    b = [random.getrandbits(8) for _ in range(6)]
    b[0] = (b[0] & 0b11111100) | 0b00000010
    return ''.join(f'{x:02x}' for x in b)

def normalize_mac_hex(s: Optional[str]) -> Optional[str]:
    """Normalize MAC-like input to 12 hex digits (no separators). Return None if invalid."""
    if not s:
        return None
    cleaned = re.sub(r'[^0-9a-fA-F]', '', s).lower()
    return cleaned if MAC_RE.match(cleaned) else None

def host_mac_set() -> Set[str]:
    """Best-effort set of host MACs (12-hex lowercase). Uses uuid.getnode() fallback."""
    macs = set()
    try:
        node = uuid.getnode()
        # uuid.getnode may return a random value; include only if it looks like a MAC
        candidate = f'{node:012x}'
        if MAC_RE.match(candidate):
            macs.add(candidate.lower())
    except Exception:
        pass
    return macs

def is_host_mac(candidate_hex: Optional[str]) -> bool:
    if not candidate_hex:
        return False
    return candidate_hex.lower() in host_mac_set()

def uuid_to_ble_le(uuid_str: str) -> bytes:
    """
    Convert canonical UUID string to BLE little-endian 16-byte representation.
    Example: "c706248c-302a-431a-9669-e77c669d2f2d"
    """
    u = uuid_str.replace("-", "")
    if len(u) != 32:
        # fallback: try to parse via uuid.UUID
        try:
            uu = uuid.UUID(uuid_str)
            u = uu.hex
        except Exception:
            return b""
    # parts: time_low(8 hex), time_mid(4), time_hi(4), rest(16)
    time_low = bytes.fromhex(u[0:8])[::-1]
    time_mid = bytes.fromhex(u[8:12])[::-1]
    time_hi = bytes.fromhex(u[12:16])[::-1]
    rest = bytes.fromhex(u[16:32])
    return time_low + time_mid + time_hi + rest

def build_adv_hex_from_components(service_uuid: str, manufacturer_prefix: str, mac_no_colons: Optional[str], extra_hex: str = "") -> str:
    # Normalize inputs
    svc = (service_uuid or "c706248c-302a-431a-9669-e77c669d2f2d")
    man = (manufacturer_prefix or "0969").lower()
    mac = normalize_mac_hex(mac_no_colons) if mac_no_colons else None
    if mac is None or is_host_mac(mac):
        mac = make_locally_administered_mac()

    # Build BLE LTV fields:
    # 1) 128-bit Service UUID field (type 0x07). Value is 16 bytes in BLE little-endian layout.
    uuid_le = uuid_to_ble_le(svc)
    svc_field = bytes([1 + len(uuid_le), 0x07]) + uuid_le if uuid_le else b""

    # 2) Manufacturer Specific Data field (type 0xFF)
    # company id must be two bytes little-endian; manufacturer_prefix may be provided as hex string
    try:
        cid = int(man, 16)
    except Exception:
        try:
            cid = int(man)
        except Exception:
            cid = 0x0969
    cid_le = cid.to_bytes(2, "little")
    manuf_payload = cid_le + bytes.fromhex(mac)
    manuf_field = bytes([1 + len(manuf_payload), 0xFF]) + manuf_payload

    # Optional extra hex appended after the standard fields (treated as raw bytes)
    extra = bytes.fromhex(extra_hex) if extra_hex else b""

    adv = svc_field + manuf_field + extra
    return adv.hex()

def mask_mac_for_log(mac12: Optional[str]) -> str:
    if not mac12 or len(mac12) != 12:
        return mac12 or ""
    parts = [mac12[i:i+2].upper() for i in range(0, 12, 2)]
    return "***:***:" + ":".join(parts[3:6])

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
        ack = bytes.fromhex("02000000")
        writer.write(pack_msg(0x06, ack))
        await writer.drain()
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
    await asyncio.sleep(MOTOR_RUN_TIME)
    device.state = "OPEN"
    device.event_counter += 1
    open_frame = build_status_frame("OPEN")
    await broadcast_notify(open_frame)

def build_status_frame(state: str) -> bytes:
    base = bytearray(STATUS_FRAME)
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

# --- New: simulator discovery task and helpers --------------------------------

def _build_sim_discovery_payload(entry: Dict, rssi: int) -> bytes:
    addr_b = entry["address"].encode("utf-8")
    name_b = entry.get("name", "").encode("utf-8")
    rssi_b = str(rssi).encode("utf-8")
    # adv is stored as a hex string (not bytes) to avoid accidental host MAC bytes
    adv_hex_b = entry.get("adv", "").encode("utf-8")
    return b"|".join([addr_b, rssi_b, name_b, adv_hex_b])

async def simulator_discovery_task(period_s: float = SIMULATOR_DISCOVERY_PERIOD_S):
    """
    Periodically broadcast synthetic discovery frames for configured simulated devices.

    TODOs:
      - Make RNG seedable via SIMULATOR_RNG_SEED for deterministic tests.
      - Add per-device spike schedules and configurable scenarios via admin API.
      - Consider emitting real advertisement_data structure or matching exact payload
        expected by Main.kt (payload compatibility).
      - Optionally provide an emit_once API for unit tests that inject timestamps.
    """
    logger.info("Simulator discovery task started (period_s=%s)", period_s)
    try:
        while True:
            # snapshot devices to avoid mutation races
            devices_snapshot = list(SIMULATED_DEVICES)
            for d in devices_snapshot:
                # small jitter around base RSSI
                jitter = d.get("jitter", 2)
                rssi = d.get("base_rssi", -80) + _sim_rng.randint(-jitter, jitter)
                payload = _build_sim_discovery_payload(d, rssi)
                # Mask any MAC in logs to avoid leaking host or device MACs
                m = re.search(r'([0-9a-fA-F]{12})', d.get("adv", ""))
                masked = mask_mac_for_log(m.group(1)) if m else ""
                logger.debug("Simulator emitting discovery addr=%s name=%s masked_mac=%s rssi=%s", d.get("address"), d.get("name"), masked, rssi)
                await broadcast_notify(payload)
            await asyncio.sleep(period_s)
    except asyncio.CancelledError:
        logger.info("Simulator discovery task cancelled")
        raise
    except Exception:
        logger.exception("Simulator discovery task error")

def simulator_emit_once(entry: Dict, rssi: Optional[int] = None, ts: Optional[float] = None):
    """
    Synchronous helper for unit tests: build a single discovery payload and return it.
    Does not perform network I/O. Tests can call broadcast_notify with the returned payload.
    TODO: Consider adding a gate.record_rssi(...) bridge that accepts injected timestamps.
    """
    if rssi is None:
        jitter = entry.get("jitter", 2)
        rssi = entry.get("base_rssi", -80) + _sim_rng.randint(-jitter, jitter)
    payload = _build_sim_discovery_payload(entry, rssi)
    return payload

# ------------------------------------------------------------------------------

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

# New admin endpoints for simulator control
async def admin_get_sim_devices(request):
    # Return a copy to avoid exposing internal structures for mutation
    return web.json_response({"devices": [
        {"address": d["address"], "name": d.get("name"), "base_rssi": d.get("base_rssi"), "jitter": d.get("jitter")}
        for d in SIMULATED_DEVICES
    ], "period_s": SIMULATOR_DISCOVERY_PERIOD_S})

async def admin_set_sim_devices(request):
    """
    Accepts JSON: { devices: [{address, name, base_rssi, jitter, adv}], period_s: float, rng_seed: int }
    Replaces the SIMULATED_DEVICES list atomically.
    """
    data = await request.json()
    devs = data.get("devices")
    period = data.get("period_s")
    seed = data.get("rng_seed")
    if devs is not None:
        # Basic validation, normalization, and safety checks
        new_list = []
        for d in devs:
            addr = d.get("address")
            if not addr:
                continue
            name = d.get("name", "")
            base_rssi = int(d.get("base_rssi", -80))
            jitter = int(d.get("jitter", 2))

            # Admin may supply adv as a hex string. If provided, validate and ensure it does not embed a host MAC.
            adv_raw = d.get("adv", "")
            adv_clean = ""
            if adv_raw:
                # normalize to lowercase string
                adv_candidate = str(adv_raw).lower()
                # try to find a 12-hex MAC inside adv; if found and it matches host, replace it
                found = re.search(r'([0-9a-fA-F]{12})', adv_candidate)
                if found:
                    mac_candidate = found.group(1).lower()
                    if is_host_mac(mac_candidate):
                        logger.warning("Admin-supplied adv contained a host MAC; replacing with synthetic MAC")
                        mac_candidate = make_locally_administered_mac()
                        adv_candidate = adv_candidate.replace(found.group(1), mac_candidate, 1)
                    adv_clean = adv_candidate
                else:
                    # no 12-hex run found; treat adv_raw as extra hex and build adv from components
                    adv_clean = build_adv_hex_from_components(d.get("service_uuid", "c706248c-302a-431a-9669-e77c669d2f2d"), d.get("manufacturer", "0969"), d.get("mac"), extra_hex=str(adv_raw))
            else:
                # no adv provided: build adv from components or generate safe mac
                adv_clean = build_adv_hex_from_components(d.get("service_uuid", "c706248c-302a-431a-9669-e77c669d2f2d"), d.get("manufacturer", "0969"), d.get("mac"))

            new_list.append({
                "address": addr,
                "name": name,
                "base_rssi": base_rssi,
                "adv": adv_clean,
                "jitter": jitter
            })
        global SIMULATED_DEVICES
        SIMULATED_DEVICES = new_list
    if period is not None:
        global SIMULATOR_DISCOVERY_PERIOD_S
        SIMULATOR_DISCOVERY_PERIOD_S = float(period)
    if seed is not None:
        global SIMULATOR_RNG_SEED, _sim_rng
        SIMULATOR_RNG_SEED = int(seed)
        _sim_rng = random.Random(SIMULATOR_RNG_SEED)
    return web.json_response({"ok": True, "devices": SIMULATED_DEVICES, "period_s": SIMULATOR_DISCOVERY_PERIOD_S, "rng_seed": SIMULATOR_RNG_SEED})

# Entrypoint: start TCP server and admin HTTP
async def start_servers():
    server = await asyncio.start_server(handle_client, TCP_HOST, TCP_PORT)
    logger.info("TCP server listening on %s:%d", TCP_HOST, TCP_PORT)

    app = web.Application()
    app.add_routes([
        web.post("/admin/set", admin_set_state),
        web.get("/admin/log", admin_get_log),
        web.get("/admin/health", admin_health),
        # simulator admin endpoints
        web.get("/admin/sim/devices", admin_get_sim_devices),
        web.post("/admin/sim/devices", admin_set_sim_devices),
    ])
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, ADMIN_HTTP_HOST, ADMIN_HTTP_PORT)
    await site.start()
    logger.info("Admin HTTP listening on %s:%d", ADMIN_HTTP_HOST, ADMIN_HTTP_PORT)

    # Start Bleak scanner when not in simulator mode; otherwise start simulator discovery
    if SIMULATOR:
        # start simulator discovery so clients receive discovery frames
        asyncio.create_task(simulator_discovery_task(period_s=SIMULATOR_DISCOVERY_PERIOD_S))
    else:
        if _BLEAK_AVAILABLE:
            asyncio.create_task(bleak_discovery_task())
        else:
            logger.warning("Started with --no-sim but bleak is not installed; install bleak to enable discovery")

    async with server:
        await server.serve_forever()

def main():
    global SIMULATOR, TCP_HOST, TCP_PORT, ADMIN_HTTP_PORT, SIMULATOR_RNG_SEED

    import argparse
    parser = argparse.ArgumentParser(description="BLE daemon with socket façade and simulator")
    parser.add_argument("--no-sim", action="store_true", help="Disable simulator and use real BLE (TODO)")
    parser.add_argument("--host", default=TCP_HOST, help="TCP host")
    parser.add_argument("--port", type=int, default=TCP_PORT, help="TCP port")
    parser.add_argument("--admin-port", type=int, default=ADMIN_HTTP_PORT, help="Admin HTTP port")
    parser.add_argument("--sim-seed", type=int, default=None, help="Optional RNG seed for simulator determinism")
    args = parser.parse_args()

    if args.no_sim:
        SIMULATOR = False
    TCP_HOST = args.host
    TCP_PORT = args.port
    ADMIN_HTTP_PORT = args.admin_port
    if args.sim_seed is not None:
        SIMULATOR_RNG_SEED = args.sim_seed
        global _sim_rng
        _sim_rng = random.Random(SIMULATOR_RNG_SEED)

    logger.info("Starting ble_daemon (simulator=%s) on %s:%d (admin %d)", SIMULATOR, TCP_HOST, TCP_PORT, ADMIN_HTTP_PORT)
    try:
        asyncio.run(start_servers())
    except KeyboardInterrupt:
        logger.info("Shutting down")

if __name__ == "__main__":
    main()
