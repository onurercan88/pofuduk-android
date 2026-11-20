# Pofuduk Android — API Integrations

Terms checked for iOS on 2026-11-14 (see the iOS repo's `API_INTEGRATIONS.md`). Re-check before release.

## Why not WeatherKit directly
iOS uses the WeatherKit framework. Android has no WeatherKit SDK; the WeatherKit REST API needs a JWT signed with a
private `.p8` key. A key inside an APK can be extracted, so it must live on a server → backend proxy (Phase A6).

## Provider order (A6)
Apple Weather via the proxy (when `pofuduk.weatherkitProxyUrl` is set at build time) → Open-Meteo. When the forecast fell
back to Open-Meteo, "now" is cross-checked against Apple Weather current conditions (`CrossCheckedWeatherRepository`,
5 s budget, precipitation wins — same rules as iOS). Without a proxy URL the app is Open-Meteo only.

## Open-Meteo — fallback (primary without a proxy; no key)
- Free API is **non-commercial only**; apps with subscriptions or ads count as commercial. Pofuduk is free, ad-free,
  without purchases → very likely non-commercial; confirm by email (draft in the iOS docs) before Play release.
- Limits: 600/min, 5,000/hour, 10,000/day. Attribution CC BY 4.0 → footer link to open-meteo.com.
- Adapter: `core/provider/OpenMeteoProvider.kt` (same request as iOS: current + hourly + daily, 5 days, place time zone,
  coordinates with 4 decimals). Current UV from the current hour; measured precipitation overrides a dry weather code.
- Cache: 30 min lifetime; offline fallback to a cache younger than 6 h, always labelled stale.

## WeatherKit REST via proxy — Phase A6
- `proxy/weatherkit-worker` (Cloudflare Worker, setup in its README) signs ES256 JWTs with the WeatherKit key
  (header `kid` + `id` = `TeamID.ServiceID`; claims `iss`, `iat`, `exp`, `sub`), calls
  `api/v1/weather/tr/{lat}/{lon}` with `timezone` (required), hourly/daily ranges from local midnight for 7 days,
  trims the response and caches per ~1 km cell. No logs, no storage. Optional `X-Pofuduk-Key` app key + per-IP rate limit.
- App: `core/provider/WeatherKitProxyProvider.kt` (coordinates rounded to 2 decimals before leaving the device, 6 s
  timeout without retry so the fallback is quick) → `AppleWeatherSnapshotBuilder` (port of iOS; REST condition codes are
  PascalCase, mapped like the framework values). Units are metric (°C, km/h, mm, mm/h).
- Attribution (required): Apple Weather mark from `https://weatherkit.apple.com/attribution/tr-TR` (light/dark logo,
  text fallback) + link to `https://weatherkit.apple.com/legal-attribution.html` in the footer (primary or "Anlık durum
  doğrulaması") and in Settings › Veri kaynakları.

## WeatherAPI.com — not shipped
A key cannot be embedded safely; only via the proxy if ever needed.

## MGM warnings
No public feed; reuse requires written permission. `ConfiguredMGMWarningProvider` stays unavailable without an authorized
feed URL and the app links the official page. Never scrape.
