# AAOS Smart Garage Door Controller

A location-aware garage door opener application built with Kotlin Multiplatform, supporting Android Automotive OS (AAOS), Android phones, and macOS for testing.

## Project Vision

This app creates a distraction-free, context-aware system for managing garage door access. When approaching home, the app detects the location and prompts to open the garage door. When leaving, it can suggest closing the door.

## Features

- **Location-Aware Triggers**: Uses geofencing to detect arrival/departure from home
- **Bluetooth Control**: Direct BLE communication with SwitchBot devices (no internet required)
- **Multi-Platform**: Android (AAOS + Phone) and macOS (testing)
- **Direct Communication**: Local Bluetooth commands sent directly to the device
- **Clean Architecture**: Modular design for easy testing and extension

## Supported Platforms

- **Current prototype**: JVM desktop proof-of-concept for local device control
- **Future target**: Android Automotive OS (AAOS) and Android phone
- **Future test/dev platform**: macOS for Bluetooth interface validation

## Architecture

The intended project architecture is Clean Architecture with a shared core and platform-specific implementations.

Current repository status:
- Root Gradle project with a Kotlin JVM app prototype
- Local Bluetooth and SwitchBot support planned as the next implementation phase
- Future modules will include shared logic and platform apps

Planned structure:

```
shared/          # Common business logic and interfaces
androidApp/      # Android application
macApp/          # macOS desktop application
```

## Key Components

### Door Controller Interface
```kotlin
interface DoorController {
    suspend fun openDoor(): Result<Unit>
    suspend fun closeDoor(): Result<Unit>
    suspend fun getStatus(): Result<DoorStatus>
}
```

### Implementations
- **MockGarageDoorController**: Initial prototype for testing
- **SwitchBotDoorController**: BLE communication with SwitchBot devices (current implementation)
- **HttpSwitchBotDoor**: Cloud API fallback (future)

## Current Implementation Status

- **Package**: `com.otabi.doorman`
- **Prototype**: Kotlin JVM app with real SwitchBot BLE control via Python bleak
- **Domain Layer**: `DoorController` interface and `BluetoothManager` abstraction
- **Platform Layer**: `MacBluetoothManager` using Python subprocess to call bleak BLE library
- **Requirements**: Python 3 with bleak installed (`pip install bleak`)
- **Status**: ✅ BLE layer working, ready for hardware testing with SwitchBot device
- **Next Step**: Test with actual SwitchBot device, then evolve to Kotlin Multiplatform

## Getting Started

### Prerequisites
- JDK 17+
- Python 3 with bleak: `pip install bleak`
- Any code editor or command line
- SwitchBot device paired and in range

### Setup
1. Clone the repository
2. Navigate to the project directory
3. Run `./gradlew clean build` to validate the current prototype

### Running the Prototype
```bash
./gradlew run
```

This will scan for SwitchBot devices via BLE, connect to the first found, and send toggle commands for open/close operations. Requires Python bleak and a SwitchBot device in range.

### Testing
See [docs/test-plan.md](docs/test-plan.md) for detailed test procedures and validation steps.

### Future Platform Targets
- Android Automotive OS (AAOS)
- Android phones
- macOS Bluetooth testing harness

## Bluetooth Setup

### SwitchBot Configuration
1. Install SwitchBot app on your phone
2. Add your garage door SwitchBot device
3. Store your device MAC address in a local ignored file:
   - Copy `device-secrets.example.properties` to `device-secrets.properties`
   - Set `switchbot.mac.address=AA:BB:CC:DD:EE:FF`
   - **Do not commit** `device-secrets.properties` to git.
   - Create the file safely from the command line:
     ```bash
     cp device-secrets.example.properties device-secrets.properties
     ```

## Development Workflow

1. **Start with macOS**: Test Bluetooth interface on Mac
2. **Mock Implementation**: Build UI with mock garage door
3. **Real Implementation**: Swap to actual SwitchBot Bluetooth
4. **Android Port**: Add Android-specific features (geofencing, AAOS)
5. **Testing**: Comprehensive testing on all platforms
6. **Keep documentation current**: Record problems and solutions in `docs/change-log.md`

## Testing

### Unit Tests
```bash
./gradlew test
```

### Integration Tests
- Bluetooth tests require physical SwitchBot device
- Use mock implementation for CI/CD

### UI Tests
```bash
./gradlew :androidApp:connectedAndroidTest
```

## Configuration

### Geofencing
- Set home location coordinates
- Configure geofence radius (recommended: 100-500 meters)
- Test geofence triggers in various conditions

### Bluetooth
- Device MAC address (found in SwitchBot app)
- Device password (set in SwitchBot app)
- Connection timeout settings

## Permissions (Android)

The app requires the following permissions:
- `ACCESS_FINE_LOCATION`: For geofencing
- `BLUETOOTH_SCAN`: For BLE device discovery
- `BLUETOOTH_CONNECT`: For BLE device connection
- `POST_NOTIFICATIONS`: For geofence notifications

## Contributing

1. Follow the established code style
2. Write tests for new features
3. Update documentation
4. Test on both macOS and Android

## Documentation

- Keep documentation aligned with code changes.
- Record key problems and solutions in `docs/change-log.md`.
- Store secret device information in `device-secrets.properties`, which is ignored by git.
- Update the README and docs whenever BLE or device behavior changes.

## Security Considerations

- BLE commands are sent directly to the device MAC address
- No sensitive data stored in plain text
- All network communications use HTTPS
- Regular security audits recommended

## Future Extensions

- Support for other garage door openers (Chamberlain, etc.)
- Voice integration (Google Assistant, Alexa)
- Multiple garage doors
- Home automation integration
- Advanced scheduling features

## License

This project is released under the GNU General Public License v3.0 (GPL-3.0).

See `LICENSE` for the full terms.

## Acknowledgments

- SwitchBot for BLE protocol documentation
- Android team for Car App Library and Geofencing API
- Kotlin Multiplatform community</content>
<parameter name="filePath">/Users/scerruti/doorman/README.md