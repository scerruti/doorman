# Project Structure Plan for AAOS Smart Garage Door Controller

## Overview
This document outlines the proposed project structure for a Kotlin Multiplatform application supporting Android (AAOS + Phone) and macOS platforms. The structure emphasizes Clean Architecture, testability, and extensibility.

## Root Directory Structure

```
doorman/
├── .cursorrules              # Copilot instructions
├── README.md                 # Project documentation
├── build.gradle.kts          # Root build configuration
├── settings.gradle.kts       # Gradle settings
├── gradle.properties         # Gradle properties
├── gradlew                   # Gradle wrapper
├── gradlew.bat               # Gradle wrapper (Windows)
├── gradle/                   # Gradle wrapper files
│   └── wrapper/
├── shared/                   # Shared Kotlin Multiplatform module
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/       # Common source
│       ├── commonTest/       # Common tests
│       ├── androidMain/      # Android-specific implementations
│       ├── androidTest/      # Android-specific tests
│       ├── nativeMain/       # macOS-specific implementations
│       └── nativeTest/       # macOS-specific tests
├── androidApp/               # Android application module
│   ├── build.gradle.kts
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/         # Java/Kotlin source
│   │   │   ├── res/          # Android resources
│   │   │   └── assets/
│   │   ├── androidTest/      # Android instrumentation tests
│   │   └── test/             # Unit tests
│   └── proguard-rules.pro
├── macApp/                   # macOS desktop application module
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── kotlin/       # Kotlin source
│       │   └── resources/    # Resources
│       └── test/             # Unit tests
├── config/                   # Configuration files
│   ├── detekt/               # Code quality rules
│   └── ktlint/
├── docs/                     # Documentation
│   ├── architecture.md
│   ├── bluetooth-protocol.md
│   └── testing.md
└── scripts/                  # Build/deployment scripts
    ├── build.sh
    └── deploy.sh
```

## Shared Module Structure (Clean Architecture)

```
shared/src/commonMain/kotlin/
├── di/                       # Dependency injection
│   ├── KoinModules.kt
│   └── PlatformModule.kt
├── domain/                   # Business logic layer
│   ├── model/                # Domain models
│   │   ├── DoorStatus.kt
│   │   ├── GeofenceEvent.kt
│   │   └── SwitchBotConfig.kt
│   ├── repository/           # Repository interfaces
│   │   └── GarageDoorRepository.kt
│   └── usecase/              # Use cases
│       ├── OpenGarageDoorUseCase.kt
│       ├── CloseGarageDoorUseCase.kt
│       └── GetDoorStatusUseCase.kt
├── data/                     # Data layer
│   ├── repository/           # Repository implementations
│   │   ├── GarageDoorRepositoryImpl.kt
│   │   └── MockGarageDoorRepository.kt
│   ├── local/                # Local data sources
│   │   ├── PreferencesDataSource.kt
│   │   └── BluetoothDataSource.kt
│   ├── remote/               # Remote data sources
│   │   └── SwitchBotApiDataSource.kt
│   └── model/                # Data models (DTOs)
│       ├── BluetoothCommand.kt
│       └── ApiResponse.kt
├── presentation/             # Presentation layer
│   ├── state/                # UI state models
│   │   ├── GarageDoorState.kt
│   │   └── GeofenceState.kt
│   ├── viewmodel/            # ViewModels
│   │   └── GarageDoorViewModel.kt
│   └── composable/           # Shared UI components
│       └── GarageDoorButton.kt
└── platform/                 # Platform abstractions
    ├── BluetoothManager.kt   # Bluetooth interface
    ├── LocationManager.kt    # Location interface
    └── NotificationManager.kt # Notification interface
```

## Android App Module Structure

```
androidApp/src/main/
├── kotlin/com/otabi/doorman/
│   ├── MainActivity.kt
│   ├── DoormanApplication.kt
│   ├── ui/                   # Android-specific UI
│   │   ├── theme/            # App theme
│   │   ├── composable/       # Compose components
│   │   │   ├── GeofenceSetupScreen.kt
│   │   │   ├── NotificationHandler.kt
│   │   │   └── CarAppScreen.kt
│   │   └── navigation/       # Navigation components
│   ├── service/              # Background services
│   │   ├── GeofenceService.kt
│   │   └── BluetoothService.kt
│   ├── receiver/             # Broadcast receivers
│   │   └── GeofenceBroadcastReceiver.kt
│   └── platform/             # Android platform implementations
│       ├── AndroidBluetoothManager.kt
│       ├── AndroidLocationManager.kt
│       └── AndroidNotificationManager.kt
└── res/
    ├── layout/               # Layouts (if needed)
    ├── values/               # Strings, colors, themes
    ├── drawable/             # Icons, images
    └── xml/                  # Service configurations
```

## macOS App Module Structure

```
macApp/src/main/
├── kotlin/com/otabi/doorman/
│   ├── main.kt               # Application entry point
│   ├── ui/                   # macOS-specific UI
│   │   ├── theme/            # Desktop theme
│   │   └── composable/       # Compose components
│   │       ├── MainWindow.kt
│   │       └── BluetoothTestScreen.kt
│   └── platform/             # macOS platform implementations
│       ├── MacBluetoothManager.kt
│       ├── MacLocationManager.kt  # Mock for testing
│       └── MacNotificationManager.kt
└── resources/                # Desktop resources
    └── icons/
```

## Implementation Plan

### Phase 1: Project Setup & macOS Bluetooth Testing
1. Set up Kotlin Multiplatform project structure
2. Implement basic shared interfaces and models
3. Create macOS app with Compose Multiplatform
4. Implement Bluetooth scanning and connection on macOS
5. Test SwitchBot BLE communication
6. Create mock garage door implementation

### Phase 2: Android Port & Core Features
1. Set up Android app module
2. Port shared logic to Android
3. Implement Android Bluetooth manager
4. Add geofencing functionality
5. Implement notification system
6. Create AAOS car app interface

### Phase 3: Integration & Testing
1. Integrate real SwitchBot Bluetooth implementation
2. Refine BLE connection stability
3. Comprehensive testing across platforms
4. Performance optimization
5. Documentation and deployment

### Phase 4: Extensions & Polish
1. Add support for other garage door systems
2. Voice integration
3. Advanced configuration options
4. Home automation integration

## Key Design Decisions

### Kotlin Multiplatform Choice
- Enables code sharing between Android and macOS
- Reduces duplication and improves maintainability
- Allows testing Bluetooth logic on macOS before Android deployment

### Clean Architecture
- Separation of concerns for better testability
- Platform abstractions for different implementations
- Domain-driven design with use cases

### Bluetooth-First Approach
- Direct BLE communication avoids internet dependency
- More reliable for garage door operations
- Better performance and security

### Mock-First Development
- Build UI and logic with mocks
- Test thoroughly before hardware integration
- Enables development without physical devices

## Dependencies

### Shared
- Koin (dependency injection)
- Kotlinx Coroutines
- Kotlinx Serialization

### Android
- Jetpack Compose
- Android Car App Library
- WorkManager
- Room (if needed for local storage)

### macOS
- Compose Multiplatform
- Kotlin/Native libraries for CoreBluetooth

## Testing Strategy

- **Unit Tests**: Business logic in shared module
- **Integration Tests**: Platform-specific implementations
- **UI Tests**: Compose testing framework
- **Manual Testing**: Physical device testing with SwitchBot

## Build Configuration

- Gradle Kotlin DSL for all build files
- Version catalogs for dependency management
- Separate build variants for debug/release
- CI/CD pipeline for automated testing

## Security Considerations

- Direct Bluetooth communication
- Secure storage of device MAC addresses
- Input validation and sanitization
- Regular dependency updates

This structure provides a solid foundation for a scalable, maintainable, and testable multiplatform application.</content>
<parameter name="filePath">/Users/scerruti/doorman/project-plan.md