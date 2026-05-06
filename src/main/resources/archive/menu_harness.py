# menu_harness.py
#
# Menu-driven test harness for:
# - configuring simulated doors
# - configuring encryption
# - sending encrypted commands
# - receiving encrypted notifications
# - driving FakeBleBackend time
#
# This is the Python-side interactive test environment.

from __future__ import annotations

import time
import sys

from test_harness import TestHarness


def print_menu():
    print("\n=== DOORMAN TEST HARNESS ===")
    print("1. Add simulated door")
    print("2. Connect to door")
    print("3. Send OPEN")
    print("4. Send CLOSE")
    print("5. Send STATUS")
    print("6. Tick simulator")
    print("7. Show notifications")
    print("8. Disconnect")
    print("9. Quit")
    print("10. List devices")
    print("============================")


def prompt(msg: str) -> str:
    return input(msg).strip()


def main():
    harness = TestHarness(use_fake_backend=True)

    current_device = None

    while True:
        print_menu()
        choice = prompt("Select option: ")

        # ------------------------------------------------------------
        # 1. Add simulated door
        # ------------------------------------------------------------
        if choice == "1":
            device_id = prompt("Device ID (MAC-like, lowercase, no colons): ")
            token = prompt("Token: ")
            secret = prompt("Secret: ")

            harness.add_simulated_door(device_id, token, secret)
            harness.save_persistent_devices()
            print(f"Added simulated door {device_id}")

        # ------------------------------------------------------------
        # 2. Connect
        # ------------------------------------------------------------
        elif choice == "2":
            user_input = prompt("Device ID or MAC to connect: ")
            try:
                mac = harness.resolve_mac(user_input)
            except KeyError as e:
                print(e)
                continue

            ok = harness.connect(mac)
            if ok:
                current_device = mac
                print(f"Connected to {mac}")
            else:
                print(f"Failed to connect to {mac}")

        # ------------------------------------------------------------
        # 3. Send OPEN
        # ------------------------------------------------------------
        elif choice == "3":
            if not current_device:
                print("No device connected")
                continue
            harness.send_command(current_device, {"cmd": "open"})
            print("Sent OPEN")

        # ------------------------------------------------------------
        # 4. Send CLOSE
        # ------------------------------------------------------------
        elif choice == "4":
            if not current_device:
                print("No device connected")
                continue
            harness.send_command(current_device, {"cmd": "close"})
            print("Sent CLOSE")

        # ------------------------------------------------------------
        # 5. Send STATUS
        # ------------------------------------------------------------
        elif choice == "5":
            if not current_device:
                print("No device connected")
                continue
            harness.send_command(current_device, {"cmd": "status"})
            print("Sent STATUS")

        # ------------------------------------------------------------
        # 6. Tick simulator
        # ------------------------------------------------------------
        elif choice == "6":
            harness.tick()
            print("Ticked simulator")

        # ------------------------------------------------------------
        # 7. Show notifications
        # ------------------------------------------------------------
        elif choice == "7":
            if not current_device:
                print("No device connected")
                continue

            frames = harness.get_notifications(current_device)
            if not frames:
                print("No notifications")
                continue

            print("\nEncrypted + Decrypted Notifications:")
            print("-----------------------------------")

            for f in frames:
                hex_str = f.hex()

                # Decrypt
                try:
                    logical = harness.decode_notification(current_device, f)
                except Exception as e:
                    print(f"  {hex_str}  (decrypt error: {e})")
                    continue

                # Internal simulator state
                internal = harness.get_simulator_state(current_device)
                logical_state = logical.get("state", "UNKNOWN")

                match = "✓ MATCH" if logical_state == internal else "✗ MISMATCH"

                print(f"Encrypted: {hex_str}")
                print(f"Decrypted: {logical}")
                print(f"Internal:  {internal}   [{match}]")
                print()

        # ------------------------------------------------------------
        # 8. Disconnect
        # ------------------------------------------------------------
        elif choice == "8":
            if not current_device:
                print("No device connected")
                continue
            harness.disconnect(current_device)
            print(f"Disconnected from {current_device}")
            current_device = None

        # ------------------------------------------------------------
        # 9. Quit
        # ------------------------------------------------------------
        elif choice == "9":
            print("Exiting.")
            sys.exit(0)

        # ------------------------------------------------------------
        # 10. List registered devices
        # ------------------------------------------------------------
        elif choice == "10":
            print("\n=== Registered Devices ===")
            for mac, crypto in harness.crypto_engines.items():
                device_id = crypto.config.device_id
                token = crypto.config.token
                secret = crypto.config.secret

                connected = harness.backend.connected.get(mac, False)
                state = harness.get_simulator_state(mac)

                print(f"MAC:        {mac}")
                print(f"Device ID:  {device_id}")
                print(f"Token:      {token}")
                print(f"Secret:     {secret}")
                print(f"Connected:  {connected}")
                print(f"State:      {state}")
                print("-----------------------------")

        else:
            print("Invalid option")


if __name__ == "__main__":
    main()
