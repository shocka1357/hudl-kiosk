# Low-cost architecture

## MVP boundary

The app is a single Android project with a small number of local layers:

```text
TV dashboard → polling coordinator → Hudl adapter → public Hudl GraphQL
       ↓                 ↓
 local event cache   local settings + PIN
       ↓
WebView player fallback ← playback status → local read-only HTTP status
       ↑
optional Accessibility best-effort foreground enforcement
```

At boot, the receiver marks the launch as a kiosk boot launch. After the first successful live schedule refresh, the dashboard opens the currently live playable event; if none is live, it opens the earliest upcoming playable event. Normal launches remain on the dashboard and continue polling until a live event begins.

An installation with no saved organization opens `FirstRunSetupActivity` before the dashboard. The local-only setup collects a public Hudl Fan URL/ID, a required admin PIN, polling and playback preferences, and optional kiosk behavior. Existing configured installations bypass onboarding during upgrades. Completing setup clears the setup task and starts the dashboard as a fresh kiosk task.

The Hudl adapter is isolated in `HudlPublicGraphQlService`. The UI does not know GraphQL field names, encoded IDs, or endpoint details.

## Cost controls

- Use Android platform networking for the first proof of concept.
- Add Media3 only when a direct playable source is confirmed.
- Store organization/settings on the device.
- Store only a salted PIN hash; never store the administrator PIN itself.
- Cache the latest public event metadata for offline dashboard startup.
- Poll from the device instead of introducing a hosted scheduler.
- Serve read-only management directly from the device on port `8787`; do not require a cloud dashboard.
- Use a fake repository for offline UI work and tests.
- Delegate bounded tasks to low-cost agents only when their write scopes do not overlap.

## Important platform limitation

Android applications cannot reliably suppress every hardware/system action from ordinary app code. A true public-display lockdown requires device-owner / dedicated-device provisioning, Android/Fire OS support, and sometimes hardware or OEM policy. The app may provide an in-app kiosk state, but it must not promise that a normal sideloaded app can block the TV's physical Home or Power controls.

## Next implementation order

1. Confirm whether `embedCodeSrc` can be replaced by a directly playable non-DRM HLS/DASH source.
2. Use the temporary WebView embed fallback while that playback contract is unknown.
3. Add Media3 playback behind a player interface only after a direct source is confirmed.
4. Validate boot handling and keep-awake behavior across target devices. (Implemented; device validation pending.)
5. Validate local PIN-protected settings with TV remotes. (Implemented; broader device validation pending.)
6. Add a stable player completion signal if Hudl's public embed exposes one.
7. Validate the read-only local management endpoint on physical TV and Fire TV networks. (Implemented; device validation pending.)
8. Add authentication before any future management endpoint is allowed to change device state.
9. Validate Accessibility-based best-effort kiosk enforcement across TV vendors. (Implemented; device validation pending.)
10. Add device-owner provisioning and true lock task mode for supported dedicated devices.
11. Test dedicated-device provisioning and Fire TV behavior before making hard kiosk claims.

## Local management boundary

`HudlKioskApplication` starts a small platform-only HTTP server while the kiosk process is alive. It listens on TCP port `8787` and exposes a human-readable dashboard at `/` plus JSON at `/api/status`. The endpoint is read-only: non-GET methods are rejected, and PIN material is never included. It has no internet relay, account, database, analytics, or cloud dependency.

Playback status is an in-process observation of the visible player and its successful autoplay trigger. It is useful health information, but it is not a remote guarantee that every downstream Hudl frame is advancing. Any future write controls require authentication and a separate security review.

## Accessibility kiosk boundary

`KioskAccessibilityService` is optional and must be enabled manually in Android Accessibility settings. When both the service and the local extreme-kiosk setting are enabled, it observes only window/package changes, filters common escape keys, and moves the existing Hudl Kiosk task back to the foreground. Its metadata sets `canRetrieveWindowContent=false`; it does not inspect screen nodes or input text.

The PIN-protected administration screen can create a five-minute maintenance window and can close that window early. This is the recovery route for settings changes and service disablement. Accessibility enforcement remains best effort. The true dedicated-device milestone is a separately provisioned device-policy controller using lock task allowlisting.
