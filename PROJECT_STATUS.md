# Pofuduk Android — Project Status

Updated: 2026-11-20

## Done (Phase A1)
- [x] Separate repo `C:\Source\PofudukAndroid` (iOS repo untouched); Kotlin + Compose, minSdk 26, target 36, AGP 8.11.1, Kotlin 2.1.21
- [x] Toolchain on Windows: JDK 17, Android SDK (platform 36, build-tools 35), Gradle 8.14.3 wrapper (`scripts\gradle.cmd`)
- [x] `core` port of iOS PofudukCore: models, summary/personality/clothing/travel engines, conversation (parser, resolver, answers),
      rain + morning notifications, subtitles + lip sync, tap reactions, provinces, Open-Meteo provider, fallback + cache repository,
      CAP/MGM alerts, location policy, demo data
- [x] `core` tests: 145 passing (+1 opt-in live test skipped). iOS-only tests not ported: WeatherAPI (not shipped), IP location
      (WeatherAPI-based), Apple Weather builder + cross-check (WeatherKit, Phase A6). Added: stored log, coarsening, XXE rejection
- [x] App: Compose home on labelled demo data (6 scenarios), TTS tr-TR with sentence + word sync, lip sync, Canvas character with
      sunglasses/umbrella/umbrella hat/scarf/kite, weather effects, tap reactions + giggle, typed questions, clothing, hourly, advice,
      dark + night theme, Remove-animations support, no permissions, no backup
- [x] Debug APK builds without warnings
- [x] Emulator run (AVD `pofuduk35`, Pixel 7, Android 35 Play image, WHPX): speaks Turkish (Google TTS embedded tr-TR voice),
      sentence + word highlight and lip sync confirmed — `docs/screenshots/`
- [x] GitHub https://github.com/onurercan88/pofuduk-android (public) + CI (`:core:test` + `:app:assembleDebug`)

## Done (Phase A2 — live forecast + location, 2026-11-20)
- [x] Live Open-Meteo via `CachedWeatherRepository` (file cache in `cacheDir`, 30 min fresh, offline ≤ 6 h labelled "Güncel olmayabilir")
- [x] Location: Pofuduk's explanation before the system prompt; `ACCESS_COARSE_LOCATION` only, foreground only, no Play Services;
      fused → network → GPS with 8 s per provider, last fix ≤ 15 min (≤ 2 h as last resort); fix coarsened to 2 decimals before use
- [x] Reverse geocoding (platform Geocoder, tr-TR) with offline nearest-province fallback (`TurkishProvinces.nearest`, tested)
- [x] Picker: 81 provinces offline + district search (Geocoder, Turkey bounding box); header opens it
- [x] States: loading / failed (retry, choose city) / needs location (allow, settings, choose city); pull-to-refresh;
      Open-Meteo attribution + retrieval time in footer; demo mode kept behind "Örnek verilerle göster"
- [x] Emulator verified: explanation → approximate-location prompt → Kadıköy, İstanbul live briefing (location 77 ms, forecast 1.1 s);
      Alanya search → Antalya forecast spoken — `docs/screenshots/`
- [x] core tests: 146 passing (+1 opt-in live skipped)

## Done (Phase A3 — voice questions, 2026-11-20)
- [x] `SpeechRecognitionService`: tr-TR, partial results into the ask field, ≤ 10 s per question, final result sent automatically;
      on-device recognizer when available, falls back to the regular service (prefer offline) with a disclosure when the
      Turkish on-device pack is missing; `RECORD_AUDIO` requested only on the first mic tap
- [x] Listening/thinking moods; suggestion chips; Return sends (3-line field)
- [x] `core/WeatherQueryService` (moved from the iOS app layer, now tested): other provinces, travel answers compared with
      the current place (origin failure tolerated), demo answers labelled; `DemoWeatherRepository`
- [x] Emulator: travel question answered with live data; mic permission prompt + honest "no Turkish speech pack" message
      (emulator has no Turkish recognition pack) — `docs/screenshots/`
- [x] core tests: 153 passing (+1 opt-in live skipped)

## Open
- [ ] Voice recognition itself not verified (emulator has no Turkish language pack / host mic) — check on a real phone
- [ ] Parser (same as iOS): "yolculuğa" is not read as travel (only "yolculuk"); consider stem matching on both platforms
- [ ] Not tested by hand yet: offline/failed screen, denied-permission → settings → resume path, Android 8–11 (requestSingleUpdate path)
- [ ] Cold start on the emulator took several seconds right after boot (Play updates running); measure on a real device in A2
- [ ] If the TTS engine restarts mid-briefing (e.g. engine update), speech stops and subtitles stay on the last sentence — consider rebinding/retry
- [ ] Phases A2–A7 (ROADMAP.md)
- [ ] Open-Meteo non-commercial confirmation (shared with iOS) or WeatherKit proxy before Play release
