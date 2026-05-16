# Doorman Implementation Plan

## Objective
Implement local SwitchBot Bluetooth support first, then extend to SwitchBot cloud/API in a later release.

## Open Items

1. **Core domain contract**
   - Finalize `GarageDoor` interface
   - Add `SwitchBotDoor` abstraction for shared device handling
   - Keep UI and triggering logic independent of the transport layer

2. **macOS test harness**
   - Implement a lightweight desktop proof-of-concept for BLE scanning
   - Validate SwitchBot device discovery, mode, and password command flow
   - Use a mock controller while the BLE layer is being stabilized

3. **Bluetooth layer**
   - Choose a platform-appropriate BLE library for macOS/JVM
   - Build a `BluetoothManager` abstraction
   - Implement BLE scanning, connect, and GATT write/read operations

4. **SwitchBot local protocol**
   - Implement password-authenticated payload formatting
   - Create a `SwitchBotCommandBuilder` module
   - Add device response validation and retry behavior

5. **Android/AAOS integration**
   - Create Android app shell with Compose UI and permissions handling
   - Add geofencing trigger logic
   - Add notification workflow and possible voice prompt support
   - Ensure AAOS compatibility with core flow and user interaction

6. **Testing strategy**
   - Use mock implementations for UI and geofence development
   - Add unit tests for domain and protocol logic
   - Add integration tests for BLE with physical SwitchBot hardware

7. **Future cloud/API release**
   - Plan an optional `HttpSwitchBotDoor` implementation
   - Use official SwitchBot cloud API/SDK for remote open/close support
   - Keep the cloud implementation parallel to the local BLE implementation

## Development Phases

### Phase 1 — Local Bluetooth Proof-of-Concept

- Build the `GarageDoor` interface and mock implementation
- Create a macOS test harness for BLE
- Validate BLE scanning for the SwitchBot device
- Implement local password-authenticated open/close commands

### Phase 2 — Android Shell and Core Flow

- Create Android project structure and basic Compose screens
- Wire mock door behavior into Android UI
- Implement geofencing and notification states
- Add permissions and background behavior

### Phase 3 — SwitchBot Real BLE Integration

- Replace mock controller with BLE-backed `BluetoothSwitchBotDoor`
- Verify device discovery, connection, and command flow
- Add status polling and error handling
- Test in a real garage environment if possible

### Phase 4 — Cloud/API Support

- Add `HttpSwitchBotDoor` for future remote operation
- Use official SwitchBot cloud API for internet-driven features
- Keep local BLE as the primary reliability path
- Add configuration to choose local vs cloud mode

### Phase 5 — Release Preparation

- Finalize `README.md`, release docs, and license
- Build Android test APK and publish to internal Play track
- Gather feedback and iterate on permissions, UI, and reliability

## Notes

- The local BLE path is the product priority.
- Cloud/API support should be modular and optional.
- The shared interface must allow switching implementations cleanly.
