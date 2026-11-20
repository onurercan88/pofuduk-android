# Pofuduk Android — Roadmap

Goal: feature parity with iOS 1.0 (App Store build 7), then a Google Play release (Turkey only, free, no ads).
Each phase ends with `:core:test` green, `:app:assembleDebug` building and, where possible, an emulator screenshot.

## A1 — Skeleton + core port + talking demo home ✅ (2026-11-20)
Gradle multi-module, `core` port with tests, Compose home on labelled demo data, TTS with sentence/word-synced
subtitles and lip sync, Canvas character with accessories, weather effects and tap reactions, dark/night theme.

## A2 — Live forecast + location ✅ (2026-11-20)
- Open-Meteo + file cache, stale/offline labelling; coarse-only foreground location after Pofuduk's explanation,
  coarsened to ~1 km before use; province picker + district search (platform Geocoder); pull-to-refresh, error states,
  attribution footer. Precise location is intentionally not requested (weather needs ~1 km).

## A3 — Voice questions ✅ (2026-11-20)
- Mic tap → `SpeechRecognizer` tr-TR (on-device first, disclosed fallback), ≤ 10 s, auto-send; context, other provinces,
  travel questions via `core/WeatherQueryService`.

## A4 — Notifications + background refresh ✅ (2026-11-20)
- Opt-in notifications (permission asked when turned on), one channel per category, quiet hours; `core/NotificationPlanner`.
- Alarms without exact-alarm permission: 10-min `setWindow` + Doze fallback `setAndAllowWhileIdle`, shown once, dropped if
  > 30 min late. Hourly WorkManager refresh from the chosen/last coarsened place (no background location).
- "Pofuduk Günaydın": morning greeting once per day (time presets/custom, auto-speak or text), optional notification; settings screen.

## A5 — Settings, travel, polish
- Settings: style, length, speech rate, voice, subtitle size, "Delete my data".
- Travel weather screen; MGM official page link (authorized feed only if permission obtained).
- Accessibility pass: TalkBack order/labels, font scale 200%, contrast audit, Remove animations.
- Adaptive launcher icon final art, splash, store screenshots (demo data, fixed clock).

## A6 — Data source upgrade (optional before release)
- Small backend proxy (e.g. Cloudflare Worker) holding the WeatherKit `.p8` key → WeatherKit REST
  (commercial use, same data as iOS). Open-Meteo stays as fallback. User sets up accounts/keys.
- Alternatively confirm Open-Meteo non-commercial status by email (draft in iOS `API_INTEGRATIONS.md`).

## A7 — Google Play release
- Play Console account (user), upload key + Play App Signing (user creates keystore; never committed).
- Data safety form (no data collected/shared), content rating, privacy policy URL (Turkish).
- New personal accounts: closed test with ≥12 testers for 14 days before production.
- Release: Turkey only, free, AAB with R8.
