#!/bin/zsh

DEVICE="1B59B01F-CCF0-7266-7928-5FD143F42BD6"
OPENER="/Users/scerruti/doorman/src/main/resources/opener_ble.py"

while true; do
  clear
  echo "=== Doorman BLE Listener Menu ==="
  echo "Device: $DEVICE"
  echo ""
  echo "1) Start listener"
  echo "2) Quit"
  echo ""
  read "choice?Select option: "

  case "$choice" in
    1)
      echo "Starting listener..."
      python3 "$OPENER" listen "$DEVICE"
      ;;
    2)
      echo "Goodbye."
      exit 0
      ;;
    *)
      echo "Invalid choice."
      ;;
  esac

  echo ""
  read -k1 "key?Press any key to continue..."
done
