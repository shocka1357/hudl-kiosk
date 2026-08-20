# Hudl broadcast playback notes

Status: public-surface reconnaissance only (2026-08-20). No login, CAPTCHA/anti-bot bypass, or private-token extraction was used. Android implementation is documented separately.

Fixture requested: <https://vcloud.hudl.com/broadcast/embed/4106310?autoplay=0>

## Confirmed observations

- A normal public HTTP GET returned `200 OK`, `text/html`, via nginx/CloudFront. The browser-control session was permission-blocked for this host, so no live browser playback or Network-panel observation was performed.
- The response did not identify itself as broadcast `4106310`: its canonical/Open Graph URL, title, and player configuration identified broadcast `3474519` instead. There was no visible `Location` header in the captured response. Treat this as an ID/fixture mismatch to verify before using either ID in code.
- The page contains a native HTML5 `<video id="blueframe-video-player">` element and loads Hudl’s `volarplayer.min.js` plus Google IMA (`ima3.js`). This is a browser HTML5 player, not an iframe-only shell.
- The page exposes a public VMAP endpoint in `player_config`, shaped like `/api/broadcast/vmap/{id}?minify_js=1`. For the ID actually present in the response, that endpoint returned XML metadata indicating an upcoming live item, a poster, ad-break definitions, and no media URL in the `<Content>` element. The response also reported `geoblocked=false` and `streaming=false` for that upcoming fixture.
- The embed HTML contained no literal HLS (`.m3u8`) or DASH (`.mpd`) media URL, no direct segment URL, and no license URL. It references a poster and the VMAP/config path instead.
- The public `token.js` asset listens for `postMessage` only from Hudl Fan origins, accepts either an empty value or a JWT-shaped value, and posts it back to the embed page as a form field named `token`. This is an authentication/entitlement integration clue; it does not establish that a token is needed for every public broadcast.
- Static inspection of the shipped player bundle found code paths for HLS, MPEG-DASH, and DRM/license fields (Widevine, PlayReady, and FairPlay). This confirms capability in the generic player bundle only; it does not prove the inspected fixture is encrypted or DRM-protected.

## Hypotheses

- The VMAP response likely acts as a session/ad/metadata bootstrap, after which the player obtains the actual source dynamically when a broadcast is live or archived.
- A native Android `VideoView`/ExoPlayer path is unlikely to be sufficient from the embed URL alone because the source is not declared in the HTML. A WebView or a separately discovered public playback/session API may be needed.
- The `postMessage` token hook may support fan-site entitlement or registration flows. It may be unused for a fully public broadcast, but this must be tested with an openly playable live or archived item.

## Unknowns

- Whether a live/archived broadcast yields a direct HLS or DASH manifest, and whether that URL is short-lived or session-bound.
- Whether playback requires WebView JavaScript, cookies, origin/referer context, a JWT, registration, purchase/entitlement, or DRM license exchange.
- Whether the `4106310` URL consistently resolves to `3474519`, or whether the response was a stale/rewritten public fixture. Do not hard-code this mapping.
- Whether the generic DRM code paths are exercised for Hudl Fan TV content.

## Low-cost recommendation

Keep the production adapter at the public `fanBroadcasts` metadata/embed-URL boundary. For one confirmed live or archived public broadcast, use a normal browser and record only the public requests made while the embed loads and starts playback. Check for a manifest URL, HTML5 media error, WebView-required behavior, and any explicit auth/entitlement/DRM response. If playback works only through the browser player, the lowest-risk Android prototype is a WebView wrapper around the embed; avoid implementing native stream extraction until a stable, authorized manifest contract is confirmed.

## Evidence captured

- Public HTTP headers/body for the requested embed URL, fetched 2026-08-20.
- Public VMAP XML for the broadcast ID present in the returned page, fetched 2026-08-20.
- Public `token.js` and `volarplayer.min.js` assets inspected 2026-08-20.
- No private credentials, bearer tokens, playback tokens, or returned analytics secrets were retained in this note.
