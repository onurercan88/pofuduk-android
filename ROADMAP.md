# Pofuduk Android — Roadmap

Goal: feature parity with iOS 1.0 (App Store build 7), then a Google Play release (Turkey only, free, no ads).
Each phase ends with `:core:test` green, `:app:assembleDebug` building and, where possible, an emulator screenshot.

## A1 — Skeleton + core port + talking demo home ✅ (2026-11-20)
Gradle multi-module, `core` port with tests, Compose home on labelled demo data, TTS with sentence/word-synced
subtitles and lip sync, Canvas character with accessories, weather effects and tap reactions, dark/night theme.

## A2 — Live forecast + location
- `INTERNET`; `OpenMeteoProvider` + `CachedWeatherRepository` (file cache in `cacheDir`), stale/offline labelling.
- Location: explanation screen before the system prompt, `ACCESS_COARSE_LOCATION` first (precise optional),
  foreground only, coordinates coarsened (~1 km) before any request; `LocationResolver` policy from core.
- Manual city picker (81 provinces, offline) + district search (geocoding provider to be chosen).
- Pull-to-refresh, error/unavailable states, attribution footer (Open-Meteo CC BY 4.0).

## A3 — Voice questions
- `RECORD_AUDIO` only while the mic button is held; `SpeechRecognizer` tr-TR, prefer offline; listening mood.
- Conversation context (ConversationManager), other provinces via repository, travel questions.

## A4 — Notifications + background refresh
- `POST_NOTIFICATIONS` (Android 13+) opt-in; channels per category; quiet hours.
- WorkManager periodic refresh using the last coarsened place (never requests location in background).
- RainNotificationEngine plans → exact-ish scheduling via WorkManager/AlarmManager (no exact-alarm permission).
- Morning briefing ("Pofuduk Günaydın") + settings.

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
