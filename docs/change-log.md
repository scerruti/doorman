# Change Log

This change log records key problems, solutions, and hardware/device information for the Doorman project.
It is intended to keep documentation up to date alongside code changes.

## 2026-05-03

### Bluetooth process update

- Added `src/main/resources/switchbot_ble.py` for macOS BLE discovery and command execution using Python `bleak`.
- Implemented exact SwitchBot UUID support for discovery and write operations.
- Added richer discovery metadata output, including:
  - Bluetooth address
  - device name
  - RSSI
  - service UUIDs
  - manufacturer data
  - SwitchBot candidate detection status
- Updated `src/main/kotlin/com/otabi/doorman/platform/MacBluetoothManager.kt` to call the Python BLE bridge and parse its JSON output.
- Updated `src/main/kotlin/com/otabi/doorman/platform/SwitchBotDoorController.kt` to print discovered BLE devices and fail clearly when no SwitchBot device is found.

### Garage door device tracking

- The garage door SwitchBot device was not in range during current validation.
- Current scan results show nearby BLE devices, but none matched SwitchBot heuristics.
- Track the following information for the garage door device:
  - device address
  - device name
  - in-range status
  - detected service UUIDs
  - manufacturer data
  - whether the device was identified as a SwitchBot candidate
  - command send outcome

### Notes

- Documentation is now required to remain current with code changes.
- The change log is the authoritative record for problems and solutions.
- Added ignored secrets support for SwitchBot MAC address in `device-secrets.properties`.
- Added `device-secrets.example.properties` as a template file.
- Updated `SwitchBotDoorController` to optionally use the configured SwitchBot MAC address if it is present.
- Confirmed no MAC address-like values were found in current repository history.
