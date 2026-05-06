#!/usr/bin/env python3
import os
import subprocess
import sys
import threading
import queue
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OPENER = os.path.join(SCRIPT_DIR, "opener_ble.py")

FAKE_MAC = "0E:12:34:56:78:9A"

# Queue for notifications coming from the listener
notify_queue = queue.Queue()

# Background listener process
listener_proc = None

def start_listener():
    global listener_proc

    if listener_proc is not None:
        return  # already running

    print("Starting background listener...")

    listener_proc = subprocess.Popen(
        ["python3", OPENER, "listen", FAKE_MAC],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )

    # Thread to read notifications line-by-line
    def reader():
        for line in listener_proc.stdout:
            line = line.strip()
            if line:
                print(f"[NOTIFY] {line}")
                notify_queue.put(line)

    threading.Thread(target=reader, daemon=True).start()


def wait_for_ack(timeout=3.0):
    """Wait for the next ACK notification."""
    try:
        ack = notify_queue.get(timeout=timeout)
        return ack
    except queue.Empty:
        return None


def run_command(cmd, expect_ack=True):
    print(f"\n>>> {cmd}")
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)

    if result.stdout:
        print(result.stdout)
    if result.stderr:
        print(result.stderr)

    if expect_ack:
        ack = wait_for_ack()
        if ack:
            print(f"[ACK RECEIVED] {ack}")
        else:
            print("[NO ACK RECEIVED]")


def menu():
    start_listener()

    while True:
        print("\n=== Doorman Test Harness ===")
        print("1. Discover devices")
        print("2. Open door")
        print("3. Close door")
        print("4. Get status")
        print("5. Quit")

        choice = input("Select an option: ").strip()

        if choice == "1":
            run_command(f"python3 \"{OPENER}\" discover", expect_ack=False)

        elif choice == "2":
            run_command(f"python3 \"{OPENER}\" send {FAKE_MAC} 570101000000")

        elif choice == "3":
            run_command(f"python3 \"{OPENER}\" send {FAKE_MAC} 570100000000")

        elif choice == "4":
            run_command(f"python3 \"{OPENER}\" send {FAKE_MAC} 5702000100f6",
                        expect_ack=False)

        elif choice == "5":
            print("Shutting down listener...")
            if listener_proc:
                listener_proc.terminate()
            print("Goodbye.")
            sys.exit(0)

        else:
            print("Invalid choice.")


if __name__ == "__main__":
    menu()
