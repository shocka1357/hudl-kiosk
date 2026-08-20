# Hudl Kiosk

Open-source Android TV / Google TV / Fire TV auto-player for a user-selected Hudl Fan organization.

## Current status

The repository currently contains a functional local-first kiosk prototype:

- D-pad-friendly TV launcher and home screen
- Simple adaptive launcher icon for TV and Android launchers
- Generic organization URL/ID input
- Public Hudl GraphQL organization lookup
- Public broadcast/event loading
- Event status, date, thumbnail, and embed-page URL mapping
- Live-now, next-up, and previous-stream dashboard sections
- Organization name and configured Hudl organization ID display
- Remote-selectable on-demand broadcast library
- Background thumbnail loading and offline event caching
- Automatic opening of newly detected live broadcasts
- Hudl-player-aware autoplay with no required start click
- Cancelable 10-second switch from VOD to a newly live broadcast
- Temporary full-screen WebView fallback
- PIN-protected local administration
- Configurable polling, autoplay, and keep-awake behavior
- Mandatory launch-on-boot behavior after organization setup
- Boot launch opens the current live broadcast or earliest upcoming playable broadcast
- Read-only local management dashboard and JSON health endpoint on port `8787`
- Optional Accessibility-based extreme kiosk enforcement with a PIN-protected maintenance window
- No school-specific production values

Native playback and dedicated-device kiosk lockdown are intentionally not claimed as complete. Boot launch is implemented but remains subject to Android/Fire TV background-launch policy. See [`docs/hudl-api.md`](docs/hudl-api.md), [`docs/hudl-playback.md`](docs/hudl-playback.md), and [`docs/architecture.md`](docs/architecture.md).

## Cost model

The MVP is designed to run at $0 in recurring infrastructure costs:

- no hosted backend
- no database
- no analytics or telemetry vendor
- no paid API key
- local settings only
- local read-only management with no hosted service

## Local management

While Hudl Kiosk is running, open `http://DEVICE_IP:8787` from a browser on the same local network. The dashboard refreshes every 10 seconds and reports:

- app version and process uptime
- device model and Android version
- configured organization name and Hudl ID
- live/VOD playback state and current event title
- polling health and cached event counts
- non-secret kiosk settings

Machine-readable status is available at `http://DEVICE_IP:8787/api/status`. The service accepts only HTTP `GET` requests and does not expose the administrator PIN or PIN hash. It is intentionally local-network-only in scope; use normal router or VLAN controls if the display network is shared with untrusted devices.

## Extreme kiosk mode

The optional extreme-kiosk setting provides best-effort enforcement on an ordinary sideloaded device. It returns the existing Hudl Kiosk task to the foreground when another app opens and filters common Home, Overview, Settings, Assistant, Menu, and out-of-app Back key events when Android delivers them to the service. The service explicitly does not request access to screen contents or typed text.

To enable it:

1. Open the PIN-protected Hudl Kiosk administration screen.
2. Enable **Extreme accessibility lockdown**.
3. Select **Accessibility settings** and enable **Hudl Kiosk lockdown** in Android. Opening this screen persists the lockdown switch immediately.
4. Return to the app and select **Lock now** to enable enforcement and close the five-minute maintenance window immediately.

Opening Android Accessibility settings from the administration screen creates a five-minute maintenance window. **Lock now** closes it early. This escape path is intentional and should be tested before deploying to a physical display.

Accessibility enforcement is not true Android lock task mode and cannot guarantee suppression of every physical or OEM-specific key. Full lockdown requires device-owner / dedicated-device provisioning.

## Local development

Open the project in Android Studio and run it on an Android TV/Google TV emulator or device. An unconfigured installation opens a TV-friendly guided setup screen. It explains how to find the public Hudl Fan organization URL, requires a 4–12 digit local admin PIN, and asks for playback, polling, screen-awake, and optional lockdown preferences. After setup, the public screen contains no configuration controls; hold OK on the small **HUDL KIOSK** label and enter the PIN to make later changes.

Fire TV sideload testing is planned after the event loader and playback path are verified.

## License

Hudl Kiosk is available under the MIT License. Hudl is a trademark of its respective owner; this community project is not affiliated with or endorsed by Hudl.
