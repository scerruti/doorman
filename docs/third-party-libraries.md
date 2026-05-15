# Third-Party Library Recommendations

This document recommends libraries for SwitchBot and Bluetooth support in the Doorman project.

## Goals

- Use mature, reliable libraries for Bluetooth connectivity
- Keep the core domain independent of transport and hardware details
- Prefer libraries that simplify BLE scanning, connection, and data exchange
- Use a thin SwitchBot protocol wrapper around BLE when possible

## Recommended Bluetooth Libraries

### JVM / macOS

- **BlueCove**
  - Mature Java Bluetooth library
  - Supports classic Bluetooth and some BLE adapters
  - Good for early proof-of-concept and desktop testing
  - Repository: https://github.com/bluecove/bluecove

- **TinyBLES** or **tinyb**
  - Lightweight Bluetooth LE helpers for Kotlin/JVM
  - May simplify BLE scanning and characteristic access

### Android

- **Android BLE API** (preferred)
  - Built-in platform support for BLE scanning, connection, and GATT operations
  - Most reliable for production-grade Android apps
  - Use only a small helper layer to manage scanning lifecycle and connection state

- **RxAndroidBLE** (optional)
  - Reactive wrapper for Android BLE APIs
  - Useful if you want observable streams for scan/connect events
  - Adds dependency overhead, so use only if the state machine becomes complex

## SwitchBot Protocol Support

### Recommended Approach

- Treat SwitchBot as a protocol layer on top of BLE
- Implement a small library/module that handles:
  - BLE device discovery by MAC address or device name
  - Payload formatting for SwitchBot open/close commands
  - Response validation and retry logic

### Library Options

- **No official Kotlin library** is currently recommended for this exact workflow.
- Use a custom wrapper to format direct BLE commands.
- For reference, the SwitchBot protocol is well documented by the community and can be implemented in a small, focused module.

## Integration Strategy

1. Keep the domain contract isolated:
   - `GarageDoor` interface
   - `SwitchBotDoor` abstraction
2. Add a `BluetoothManager` implementation for each platform
3. Add a `SwitchBotCommandBuilder` module for protocol-specific bytes
4. Add a `MockGarageDoorController` for offline development and unit tests

## Notes

- The macOS test harness should focus on BLE connectivity and SwitchBot payload validation.
- Android should use native BLE APIs for stability and permission handling.
- Any library choice should be wrapped behind platform-specific interfaces so the app architecture remains clean.
