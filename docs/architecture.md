# Low-cost architecture

## MVP boundary

The app is a single Android project with a small number of local layers:

```text
TV dashboard → polling coordinator → Hudl adapter → public Hudl GraphQL
       ↓                 ↓
 local event cache   local settings + PIN
       ↓
WebView player fallback
```

At boot, the receiver marks the launch as a kiosk boot launch. After the first successful live schedule refresh, the dashboard opens the currently live playable event; if none is live, it opens the earliest upcoming playable event. Normal launches remain on the dashboard and continue polling until a live event begins.

The Hudl adapter is isolated in `HudlPublicGraphQlService`. The UI does not know GraphQL field names, encoded IDs, or endpoint details.

## Cost controls

- Use Android platform networking for the first proof of concept.
- Add Media3 only when a direct playable source is confirmed.
- Store organization/settings on the device.
- Store only a salted PIN hash; never store the administrator PIN itself.
- Cache the latest public event metadata for offline dashboard startup.
- Poll from the device instead of introducing a hosted scheduler.
- Make remote management optional and local-first; do not require a cloud dashboard.
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
7. Revisit remote management as an authenticated, opt-in local endpoint.
8. Test dedicated-device provisioning and Fire TV behavior before making kiosk claims.
