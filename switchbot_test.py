import asyncio
import logging
from bleak import BleakScanner
from switchbot import SwitchbotRelaySwitch

# --- CONFIGURATION ---
DEVICE_ADDR = "1B59B01F-CCF0-7266-7928-5FD143F42BD6"

# Note: The library uses 'key' and 'key_id'
ENCRYPTION_KEY = "2c3ed4a4f9cfa20ddc5f60c3c48850dd"
KEY_ID = "51"

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("PY_SWITCHBOT_RELAY")

async def pulse_relay(relay):
    """Simulate a momentary wall button press."""
    await relay.turn_on()
    await asyncio.sleep(1.0)
    await relay.turn_off()

async def run_test():
    logger.info(f"Scanning for {DEVICE_ADDR}...")
    ble_device = await BleakScanner.find_device_by_address(DEVICE_ADDR, timeout=10.0)
    
    if not ble_device:
        logger.error("Could not find Relay. Ensure Bluetooth is ON and device is in range.")
        return

    # 1. Initialize as RelaySwitch (Matches your cba2 UUIDs)
    # The parameters are 'key' and 'key_id'
    relay = SwitchbotRelaySwitch(ble_device, encryption_key=ENCRYPTION_KEY, key_id=KEY_ID)

    try:
        # 2. Connect and Fetch Initial Status
        logger.info("Connecting and fetching initial status...")
        # update() connects and initializes the state
        await relay.update()
        
        # get_basic_info() is a coroutine, so we MUST await it before calling .get()
        basic_info = await relay.get_basic_info()
        logger.info(f"Initial State Data: {basic_info}")

        # 3. Open the Door (Turn Relay ON)
        logger.info("Step 1: Sending TURN_ON (Open) command...")
        success = await relay.turn_on()
        if not success:
            logger.warning("Command failed. This often happens if the Mac is not bonded.")
        # 3. Open the Door (Pulse Relay)
        logger.info("Step 1: Pulsing relay to OPEN door...")
        await pulse_relay(relay)

        # 4. Monitor State change
        logger.info("Waiting 10 seconds for door movement...")
        for _ in range(5):
            await asyncio.sleep(2)
            # Refresh status from the device
            status_data = await relay.get_basic_info()
            # Relays usually report 'on' or 'off'
            current_state = status_data.get("is_on")
            logger.info(f"Current Relay State (is_on): {current_state}")
        logger.info("Waiting 15 seconds for door movement...")
        await asyncio.sleep(15)

        logger.info("✅ Open cycle complete. Pausing...")
        await asyncio.sleep(5)

        # 5. Close the Door (Turn Relay OFF)
        logger.info("Step 2: Sending TURN_OFF (Close) command...")
        await relay.turn_off()
        # 5. Close the Door (Pulse Relay again)
        logger.info("Step 2: Pulsing relay to CLOSE door...")
        await pulse_relay(relay)

        # 6. Final Status Check
        await asyncio.sleep(2)
        final_info = await relay.get_basic_info()
        logger.info(f"Final State: {final_info}")
        logger.info("✅ Test sequence finished.")

    except Exception as e:
        logger.error(f"An error occurred: {e}")
    finally:
        logger.info("Test complete.")

if __name__ == "__main__":
    asyncio.run(run_test())