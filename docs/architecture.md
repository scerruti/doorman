# Doorman Architecture Documentation

## Overview

This documentation describes the architecture for the Doorman smart garage door controller project. It is intended to help reviewers and developers understand the system structure, key components, and data flow.

## Project Structure

The repository uses a modular project layout with a dedicated documentation tree under `docs/`.

- `docs/architecture.md` — architecture overview and design rationale
- `docs/uml/class-diagram.mmd` — class diagram in Mermaid format
- `docs/uml/sequence-diagram.mmd` — sequence diagram in Mermaid format

## Architectural Goals

- **Separation of concerns** between domain logic, device communication, and UI/interaction layers.
- **Testability** with mock implementations for initial development.
- **Extensibility** so new door controllers and protocols can be added later.
- **Platform flexibility**: macOS for Bluetooth testing, Android/AAOS for the consumer-facing app.

## Key Components

### Core Domain

- `GarageDoorInterface` - abstract contract for door operations
- `SwitchBotDoor` - shared SwitchBot-specific behavior
- `BluetoothSwitchBotDoor` - direct BLE implementation
- `HttpSwitchBotDoor` - cloud/API fallback implementation
- `MockGarageDoorController` - test-first implementation for development and validation

### Platform Layers

- **macOS test harness**: initial proof-of-concept for Bluetooth payloads and device discovery
- **Android app**: final target with geofencing, notifications, and AAOS integration

### Security

- Require SwitchBot device password mode for secure local BLE control
- Use timestamped authentication tokens when operating over Bluetooth
- Avoid unencrypted replay-prone payloads in production

## Future Cloud/API Support

- Local BLE support is the first priority for reliability and availability
- Plan a future `HttpSwitchBotDoor` implementation for official SwitchBot cloud/API access
- Keep the cloud path modular so the app can fallback to local BLE when needed
- Use the cloud implementation for remote access, analytics, and optional voice integrations

## Design Summary

This project is built around a clean interface for door control with platform-specific implementation details isolated from the workflow logic. The primary development path is:

1. Build and validate core contract and mock behavior
2. Test Bluetooth command behavior on macOS
3. Port the same domain model into Android
4. Add location and car-specific triggers

## Diagrams

### Class Diagram

See `docs/uml/class-diagram.mmd`

### Sequence Diagram

See `docs/uml/sequence-diagram.mmd`

## Next Steps

- Add a `LICENSE` file for public sharing with share-alike attribution
- Add Play Store release documentation once Android architecture is established
- Expand docs with mobile UI flow and AAOS-specific integration notes
