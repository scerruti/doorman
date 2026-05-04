# Release and Play Store Distribution Guide

## Goal
Prepare the Doorman Android app for Play Store testing and future distribution.

## Release Strategy

- **Initial release**: internal test track or closed testing
- **Later release**: public production track
- **Pricing**: low-cost paid app to recognize convenience and support maintenance

## Play Store Checklist

### App setup
- Create a Play Console account
- Create a new app entry for Doorman
- Set app category to `Tools` or `Auto & Vehicles`
- Configure app details, screenshots, and privacy policy URL

### App signing
- Use Google Play App Signing
- Generate a secure keystore for release builds
- Keep signing credentials in a secure location

### Versioning
- Use semantic versioning: `1.0.0`, `1.0.1`, etc.
- Increment `versionCode` for each Play Store upload

### Testing tracks
- **Internal test**: fast validation with limited users
- **Closed test**: invite a small group for usability and real-device feedback
- **Open test**: broader rollout before production

### Pricing
- Set a low price point that reflects convenience
- Consider regional pricing and taxes
- Ensure the app is available for countries where you want testing

### Permissions and security
- Declare runtime permissions clearly in the Play Console
- Provide justification for `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`, and `POST_NOTIFICATIONS`
- Add a privacy policy explaining local Bluetooth control and location usage

### Compliance
- Confirm the app follows Google Play policies
- Ensure no blocked or sensitive APIs are used without explicit declaration
- If using user location, include a privacy policy and relevant disclosures

## Documentation to include
- `README.md` overview
- `docs/architecture.md` architecture summary
- `docs/release.md` release guide
- `LICENSE` with GPLv3 share-alike terms

## Suggested next steps
1. Build a minimal Android app shell and connect it to the shared logic
2. Create Play Store screenshots and store listing copy
3. Publish an internal test build
4. Use feedback to refine permissions, UI, and release readiness
