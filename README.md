# Hudl Kiosk

Open-source Android TV / Google TV / Fire TV auto-player for a user-selected Hudl Fan organization.

## Current status

The repository currently contains a functional local-first kiosk prototype:

- D-pad-friendly TV launcher and home screen
- Generic organization URL/ID input
- Public Hudl GraphQL organization lookup
- Public broadcast/event loading
- Event status, date, thumbnail, and embed-page URL mapping
- Live-now, next-up, and previous-stream dashboard sections
- Organization name and configured Hudl organization ID display
- Remote-selectable on-demand broadcast library
- Background thumbnail loading and offline event caching
- Automatic opening of newly detected live broadcasts
- Autoplay-enabled Hudl embeds with no required start click
- Cancelable 10-second switch from VOD to a newly live broadcast
- Temporary WebView fallback with a return-to-dashboard control
- PIN-protected local administration
- Configurable polling, autoplay, and keep-awake behavior
- Mandatory launch-on-boot behavior after organization setup
- Boot launch opens the current live broadcast or earliest upcoming playable broadcast
- No school-specific production values

Native playback and dedicated-device kiosk lockdown are intentionally not claimed as complete. Boot launch is implemented but remains subject to Android/Fire TV background-launch policy. See [`docs/hudl-api.md`](docs/hudl-api.md), [`docs/hudl-playback.md`](docs/hudl-playback.md), and [`docs/architecture.md`](docs/architecture.md).

## Cost model

The MVP is designed to run at $0 in recurring infrastructure costs:

- no hosted backend
- no database
- no analytics or telemetry vendor
- no paid API key
- local settings only
- optional remote management deferred until its authentication and deployment model are clear

## Local development

Open the project in Android Studio and run it on an Android TV/Google TV emulator or device. For first-time setup, focus the small **HUDL KIOSK** label and hold the remote's OK button. Enter a Hudl Fan organization URL or numeric ID, choose kiosk behavior, and set a 4–12 digit local PIN. After setup, the public screen contains no configuration controls.

Fire TV sideload testing is planned after the event loader and playback path are verified.

## License

Hudl Kiosk is available under the MIT License. Hudl is a trademark of its respective owner; this community project is not affiliated with or endorsed by Hudl.
