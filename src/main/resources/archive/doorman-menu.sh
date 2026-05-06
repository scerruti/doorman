#!/bin/zsh

DEVICE="1B59B01F-CCF0-7266-7928-5FD143F42BD6"
OPENER="/Users/scerruti/doorman/src/main/resources/opener_ble.py"

while true; do
  clear
  echo "=== Doorman BLE Menu ==="
  echo "Device: $DEVICE"
  echo ""
  echo "1) pb_status   (prebuilt real frame)"
  echo "2) pb_open     (prebuilt real frame)"
  echo "3) pb_close    (prebuilt real frame)"
  echo "4) status      (crypto path)"
  echo "5) open        (crypto path)"
  echo "6) close       (crypto path)"
  echo "7) Raw hex"
  echo "8) Quit"
  echo ""
  read "choice?Select option: "

  case "$choice" in
    1)
      python3 "$OPENER" send "$DEVICE" pb_status
      ;;
    2)
      python3 "$OPENER" send "$DEVICE" pb_open
      ;;
    3)
      python3 "$OPENER" send "$DEVICE" pb_close
      ;;
    4)
      python3 "$OPENER" send "$DEVICE" status
      ;;
    5)
      python3 "$OPENER" send "$DEVICE" open
      ;;
    6)
      python3 "$OPENER" send "$DEVICE" close
      ;;
    7)
      read "hex?Enter raw hex: "
      python3 "$OPENER" send "$DEVICE" "$hex"
      ;;
    8)
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
