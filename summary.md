Project: AAOS Smart Garage Door Controller

Target Platform: Android (Mobile) & Android Automotive OS (Equinox EV)
Core Hardware: SwitchBot Garage Door Opener (GDO)

1. Project Vision & Use Cases

The goal is to create a distraction-free, context-aware application that manages physical access to the home garage.

Primary Trigger (Arrival): Using Android Geofencing, the app detects when the vehicle enters a defined radius around the Oceanside home. It immediately initiates a background connection sequence and prompts the driver on the AAOS screen to open the door.

Secondary Trigger (Departure): Using the AAOS CarPropertyManager, the app detects a gear shift from Reverse to Drive. If the vehicle is within the home geofence and the door is open, it prompts the user to close it.

2. Software Architecture: Separation of Concerns

To keep the application flexible and testable, the software design separates the intent (opening a door) from the mechanism (how the message is sent).

The system relies on a central GarageDoor interface. This allows the core UI and geofencing logic to be built and tested entirely using a MockGarageDoorController within the Android Studio emulator, before any real-world hardware or networking is introduced.

Once the UI is finalized, the mock controller can be swapped out for one of two physical implementations:

HttpSwitchBotDoor: Uses the SwitchBot Cloud API over the internet.

BluetoothSwitchBotDoor: Uses direct Bluetooth Low Energy (BLE) to communicate locally with the GDO.

3. The Connectivity Dilemma: Cloud vs. Edge

Garages are notorious dead zones for home Wi-Fi. While connecting to the SwitchBot Cloud API is straightforward, relying on the internet for an immediate physical action when pulling into the driveway is prone to latency and failure.

The Local Solution: Communicating directly over Bluetooth Low Energy (BLE) is the preferred method. When the vehicle breaches the geofence, the app begins scanning for the specific MAC address of the garage's SwitchBot.

The UI State Flow:
To ensure a smooth user experience, the system uses dynamic notification states:

Geofence Breached: Notification appears reading "Approaching Home..." with a disabled button reading "Searching...".

BLE Discovery: Once the phone's radio detects the SwitchBot, the background service updates the existing notification.

Ready State: The notification text changes to "Garage in Range" and the button becomes active, reading "Open Door."