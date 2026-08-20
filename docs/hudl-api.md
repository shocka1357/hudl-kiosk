# Hudl Fan API notes

Status: public-surface reconnaissance only (2026-08-20). The Mott page is a **test fixture**, not production behavior.

Fixture: <https://fan.hudl.com/usa/mi/warren/organization/10904/mott-high-school>

## Confirmed observations

- The fixture returns HTTP 200 HTML from CloudFront and identifies itself as a Next.js app (`x-powered-by: Next.js`). The response is about 202 KB and includes the page title, description, Open Graph metadata, and a `schema.org/HighSchool` JSON-LD object.
- The public metadata identifies the organization as `Warren Mott High School`, organization/path ID `10904`, location Warren, MI, and the public alias <https://fan.hudl.com/warrenmott>. The page description says the site supports livestreams, highlights, and upcoming events; this is a capability statement, not evidence that this fixture currently has any of those records.
- The HTML contains `data-dgst="BAILOUT_TO_CLIENT_SIDE_RENDERING"` and loads a route chunk from Hudl’s asset host. Therefore the initial document is not a reliable source for the rendered event list; browser-side requests are expected.
- The route chunk contains these endpoint constructors (shown in shipped JavaScript):

  - `https://www.hudl.com/api/public/graphql/query`
  - `https://www.hudl.com/api/graphql/query`
  - `https://www.hudl.com/api/admin/graphql/query`

  The public constructor is the only one in scope for this project. The route-specific chunk delegates operation documents to a shared client chunk.
- A direct unauthenticated request to the public endpoint with no query body returned a GraphQL/HotChocolate response rather than a login page. The route chunk and shared client chunk contain the operation documents used by the public Fan client.
- A confirmed public organization lookup is `Web_Fan_GetSchools_r1`. Supplying `schoolIds: ["10904"]` returned a school record whose GraphQL ID was `U2Nob29sMTA5MDQ=`.
- A confirmed public broadcast lookup is `Web_Fan_GetFanBroadcasts_r1`. Its input requires `sortType: BROADCAST_DATE`, `sortByAscending`, and `broadcastStatusFilter`; the currently observed organization filter is the singular encoded ID value in the field named `schoolIds`.
- For the test fixture, the broadcast query returned `82` records. Each record included status, title, UTC date, thumbnails, and an `embedCodeSrc` such as `https://vcloud.hudl.com/broadcast/embed/4223380?autoplay=0`. This is a public embed-page URL, not yet a confirmed native stream manifest.
- The fixture HTML contains no literal `m3u8`, `mpd`, `manifest`, playback URL, DRM license URL, bearer token, or event/live/VOD record. It also contains no inline JSON record for an event or stream.

## Hypotheses (not yet verified)

- The organization/event/live/VOD data is fetched by client-side GraphQL after hydration from the public endpoint above.
- A rendered “watch” action may lead to a separate Hudl playback service, and a stream may be represented by a short-lived URL or session response. HLS, DASH, DRM, and entitlement behavior cannot be selected from the broadcast list alone.
- The shipped player bundle includes generic Video.js playback/analytics code, but that is not evidence that this organization has a playable asset or that a particular protocol/DRM mode is used.

## Unknowns / implementation boundary

- Required headers beyond ordinary JSON/origin/referer headers, pagination limits, caching, rate limits, and whether event data is public for every organization remain unknown.
- The existence and shape of live events, VODs, manifests, subtitles, ad insertion, DRM, and authentication/entitlement checks remain unknown. `embedCodeSrc` must be treated as a browser/embed fallback until the playback page is inspected.
- Do not scrape or replay private/authenticated requests, bypass anti-bot controls, guess undocumented operations, or hard-code organization `10904` in production behavior.

## Recommended low-cost next step

Use a normal browser session on this public fixture and record only the Network requests made while a broadcast embed loads (no login, automation bypass, or playback-token extraction). Determine whether the embed page exposes a directly playable media source, requires a browser player, or applies DRM/entitlement checks. Keep the low-cost production adapter limited to event metadata until that answer is known.

## Evidence captured

- Public page HTML and headers fetched on 2026-08-20: the fixture URL above.
- Route chunk inspected: <https://assets.hudl.com/_next/v2/fan/_next/static/chunks/app/%5Bcountry%5D/%5Bsubdivision%5D/%5Bcity%5D/organization/%5BschoolId%5D/%5BschoolName%5D/page-1918ec5a2f042a1a.js>.
- Shared client chunk inspected: <https://assets.hudl.com/_next/v2/fan/_next/static/chunks/235-53ba3827dc33d0d1.js>.
- Direct public GraphQL responses recorded for the two operations above on 2026-08-20.
- Browser text extraction reported that JavaScript is disabled and warned that additional content may be present when JavaScript is enabled; this is consistent with the HTML’s client-rendering bailout.
