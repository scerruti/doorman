# Test Plan: SwitchBot BLE Garage Door Controller

## Current Status
✅ **BLE Layer Implemented**: Kotlin JVM app successfully calls Python bleak for BLE operations  
✅ **Discovery Working**: Scans for BLE devices and filters for SwitchBot devices  
✅ **Command Protocol**: Sends correct SwitchBot Bot toggle commands (0x57 0x01)  
✅ **Error Handling**: Gracefully handles missing devices and connection failures  
✅ **Testing Ready**: App runs cleanly and is ready for hardware testing with real SwitchBot device

## Prerequisites
- **Hardware**: SwitchBot Bot device, paired with your Mac (via System Settings > Bluetooth)
- **Software**:
  - JDK 17+ installed
  - Python 3 installed (`python3 --version`)
  - Bleak library: `pip3 install bleak`
  - Any code editor (VS Code, IntelliJ IDEA, etc.) or command line
- **Environment**: SwitchBot device in BLE range (within 10-20 meters, clear line of sight)

## Test Setup
1. Clone/download the project
2. Navigate to the project directory
3. Run `./gradlew clean build` - should succeed
4. Ensure SwitchBot device is powered on and discoverable

## Test Cases

### Test Case 1: BLE Device Discovery
**Objective**: Verify the app can find SwitchBot devices via BLE

**Steps**:
1. Run `./gradlew run`
2. Observe console output for "Scanning for BLE devices..."
3. Wait 5-10 seconds for scan completion
4. Check output for "Found device: SwitchBot Bot (XX:XX:XX:XX:XX:XX)"

**Expected Result**:
- Device discovery succeeds
- SwitchBot device appears in results
- No errors in console

**Pass/Fail Criteria**:
- PASS: Device found and listed
- FAIL: No devices found, or error messages

### Test Case 2: Door Open Command
**Objective**: Test sending open/toggle command to SwitchBot

**Steps**:
1. Ensure SwitchBot device is in range and discoverable
2. Run `./gradlew run`
3. Observe "Connecting to [device address]..."
4. Watch for "Command sent" confirmation
5. Physically check if garage door opens (or SwitchBot moves)

**Expected Result**:
- BLE connection succeeds
- Command transmission completes without errors
- SwitchBot device activates (physical movement/beep)

**Pass/Fail Criteria**:
- PASS: Command sent successfully, device responds
- FAIL: Connection fails, or command send errors

### Test Case 3: Door Close Command
**Objective**: Test subsequent close command

**Steps**:
1. After Test Case 2, run `./gradlew run` again
2. Same as Test Case 2, but verify door closes

**Expected Result**:
- Second command succeeds
- Door closes (or SwitchBot toggles back)

**Pass/Fail Criteria**:
- PASS: Toggle works in both directions
- FAIL: Second command fails

### Test Case 4: Error Handling
**Objective**: Test behavior when device is out of range

**Steps**:
1. Move SwitchBot device out of BLE range (or turn off)
2. Run `./gradlew run`
3. Observe error messages

**Expected Result**:
- Graceful failure with clear error message
- No crashes or hangs

**Pass/Fail Criteria**:
- PASS: App handles errors gracefully
- FAIL: App crashes or hangs

## Expected Console Output

### Without SwitchBot Device (Current State)
```
=== Doorman Garage Door Controller (SwitchBot BLE) ===
Testing garage door operations...

1. Checking initial status:
Status: UNKNOWN

2. Opening door:
Attempting to open door via SwitchBot...
Failed to open door: No SwitchBot device found

3. Status after opening:
Status: UNKNOWN

4. Closing door:
Attempting to close door via SwitchBot...
Failed to close door: No SwitchBot device found

5. Final status:
Status: UNKNOWN
```

### With SwitchBot Device Present
```
=== Doorman Garage Door Controller (SwitchBot BLE) ===
Testing garage door operations...

1. Checking initial status:
Status: UNKNOWN

2. Opening door:
Attempting to open door via SwitchBot...
Door open command sent successfully

3. Status after opening:
Status: UNKNOWN

4. Closing door:
Attempting to close door via SwitchBot...
Door close command sent successfully

5. Final status:
Status: UNKNOWN
```

**Note**: SwitchBot Bot devices are stateless toggles, so status always shows UNKNOWN. Physical device movement indicates success.

## Troubleshooting
- **No devices found**: Check BLE permissions, device power, range
- **Connection fails**: Ensure device is paired, try restarting Bluetooth
- **Python errors**: Verify `pip3 install bleak` worked
- **Build fails**: Check JDK version, run `./gradlew --version`

## Success Criteria
- All test cases pass
- BLE discovery and control work reliably
- App runs without crashes
- Physical SwitchBot device responds to commands

## Next Steps After Testing
- Add device selection/pairing UI
- Implement status reading (if supported)
- Move to Kotlin Multiplatform for Android/iOS
- Add geofencing and notification features

This test plan focuses on validating the core BLE functionality before expanding to the full application.