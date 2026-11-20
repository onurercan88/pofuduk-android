# Pofuduk Android — API Integrations

Terms checked for iOS on 2026-11-14 (see the iOS repo's `API_INTEGRATIONS.md`). Re-check before release.

## Why not WeatherKit directly
iOS uses the WeatherKit framework. Android has no WeatherKit SDK; the WeatherKit REST API needs a JWT signed with a
private `.p8` key. A key inside an APK can be extracted, so it must live on a server → backend proxy (Phase A6).

## Open-Meteo — primary for A2 (no key)
- Free API is **non-commercial only**; apps with subscriptions or ads count as commercial. Pofuduk is free, ad-free,
  without purchases → very likely non-commercial; confirm by email (draft in the iOS docs) before Play release.
- Limits: 600/min, 5,000/hour, 10,000/day. Attribution CC BY 4.0 → footer link to open-meteo.com.
- Adapter: `core/provider/OpenMeteoProvider.kt` (same request as iOS: current + hourly + daily, 5 days, place time zone,
  coordinates with 4 decimals). Current UV from the current hour; measured precipitation overrides a dry weather code.
- Cache: 30 min lifetime; offline fallback to a cache younger than 6 h, always labelled stale.

## WeatherKit REST via proxy — Phase A6
- Proxy (e.g. Cloudflare Worker) signs requests with the user's WeatherKit key; the app calls the proxy only.
- The proxy must not log coordinates; coordinates are rounded to 2 decimals (~1 km) by the app.

## WeatherAPI.com — not shipped
A key cannot be embedded safely; only via the proxy if ever needed.

## MGM warnings
No public feed; reuse requires written permission. `ConfiguredMGMWarningProvider` stays unavailable without an authorized
feed URL and the app links the official page. Never scrape.
