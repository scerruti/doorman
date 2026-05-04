Project: AAOS Smart Garage Door Controller

Target Platform: Android (Mobile) & Android Automotive OS (Equinox EV)
Core Hardware: SwitchBot Bot (Physical button pusher)

1. Project Vision & Use Cases

The goal is to create a distraction-free, context-aware application that manages physical access to the home garage.

Primary Trigger (Arrival): Using Android Geofencing, the app detects when the vehicle enters a defined radius around the Oceanside home. It immediately initiates a background connection sequence and prompts the driver on the AAOS screen to open the door.

Secondary Trigger (Departure): Using the AAOS CarPropertyManager, the app detects a gear shift from Reverse to Drive. If the vehicle is within the home geofence and the door is open, it prompts the user to close it.

2. Software Architecture: Separation of Concerns

To keep the application flexible and testable, the software design separates the intent (opening a door) from the mechanism (how the message is sent).

The system relies on a central GarageDoor interface. This allows the core UI and geofencing logic to be built and tested entirely using a MockGarageDoorController within the Android Studio emulator, before any real-world hardware or networking is introduced.

Once the UI is finalized, the mock controller can be swapped out for one of two physical implementations:

HttpSwitchBotDoor: Uses the SwitchBot Cloud API over the internet.

BluetoothSwitchBotDoor: Uses direct Bluetooth Low Energy (BLE) to communicate locally with the Bot.

3. The Connectivity Dilemma: Cloud vs. Edge

Garages are notorious dead zones for home Wi-Fi. While connecting to the SwitchBot Cloud API is straightforward, relying on the internet for an immediate physical action when pulling into the driveway is prone to latency and failure.

The Local Solution: Communicating directly over Bluetooth Low Energy (BLE) is the preferred method. When the vehicle breaches the geofence, the app begins scanning for the specific MAC address of the garage's SwitchBot.

The UI State Flow:
To ensure a smooth user experience, the system uses dynamic notification states:

Geofence Breached: Notification appears reading "Approaching Home..." with a disabled button reading "Searching...".

BLE Discovery: Once the phone's radio detects the SwitchBot, the background service updates the existing notification.

Ready State: The notification text changes to "Garage in Range" and the button becomes active, reading "Open Door."

4. The Critical Security Vulnerability

During the design phase, a major security flaw in the default hardware configuration was identified.

Out of the box, a SwitchBot operates with no local authentication. It listens constantly for a static, unencrypted byte payload (0x57, 0x01, 0x00).

The Replay Attack Risk

Because the command is static, anyone with a free Bluetooth scanning app on their phone can walk past the garage, broadcast that exact string of bytes, and trigger the mechanical arm. The Bot has no way of verifying who is sending the command.

Cloud Security vs. Local Security

A common misconception is that adding a Google Assistant Voice PIN secures the device.

Voice PIN (Cloud): Only protects the internet-based pathway. It stops someone from standing outside a window and yelling, "Hey Google, open the garage."

Device Password (Local): Protects the physical Bluetooth radio.

The Bot's Bluetooth receiver remains active 24/7. Even if the device is exclusively controlled via the internet or Google Home, the local, unsecured Bluetooth pathway remains wide open.

The Required Mitigation

To secure the home, the Password feature must be manually enabled within the specific device settings in the SwitchBot mobile app.

Enabling this changes the communication protocol. The static three-byte payload is rejected. Instead, the controlling app (our custom Kotlin app) must generate a dynamic, one-time authentication token. This token is created by running the user's PIN, the Bot's MAC address, and the current timestamp through a cryptographic hash function. Because the timestamp changes constantly, a recorded command cannot be replayed by a bad actor later, effectively securing the local perimeter.