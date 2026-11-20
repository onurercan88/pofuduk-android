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

## Done (Phase A4 — notifications + background refresh, 2026-11-20)
- [x] `core/NotificationPlanner` (rain/wind/storm/temperature + morning, dedup log; never from demo data) + tests — core 156 tests
- [x] App: `AppGraph` (shared by UI + background), channels per category, `NotificationScheduler` (window alarm + Doze
      fallback, shown-once record, > 30 min late → dropped), `RefreshWorker` hourly (network constraint, only when enabled)
- [x] "Pofuduk Günaydın" in the app (once per day, auto-speak or text-only) and on resume; settings screen (morning time,
      auto-speak, morning notification, categories, lead time, probability, quiet hours, planned list, honest "how it works")
- [x] Emulator (clock moved by hand): permission asked only when turned on; storm at 16.00 → alarms for 15.30 → notification
      delivered inside the 10-min window, second alarm skipped ("already shown"); morning greeting spoken at 08.00
- [x] Fixes found while testing: replanning no longer cancels due-but-undelivered (in-flight) alarms; notification texts use clock
      times on Android (`RainNotificationEngine(relativeTimes = false)`) because delivery may shift; "denied" note only after asking;
      Android wording for denied location (no IP option)

## Done (Phase A5 — settings, travel, polish, 2026-11-20)
- [x] Settings: style, length, Turkish voice picker (installed offline voices), speech rate, voice preview, subtitle size
      (also scales with system font), location, sources (Open-Meteo, MGM), privacy note, "Verilerimi sil" (confirmed;
      clears location, cache, notification/morning settings, personalization, context; cancels pending notifications), version
- [x] Travel screen: destination search (provinces offline + geocoder, Turkey only), departure/return dates, comparison
      with the current place, daily rows, packing list, "Pofuduk anlatsın"; `core/TravelWeatherService` + tests
- [x] Home: travel entry card, MGM official-page card (no unofficial warnings), subtitle size applied
- [x] Fix: cache entries dated in the future (device clock moved back) are never treated as fresh (+test) — core 159 tests
- [x] Accessibility: 200% font checked on home (wraps, nothing clipped), labels via uiautomator, decorative emoji hidden

## Done (Phase A7 prep — release readiness, 2026-11-20)
- [x] Version 1.0.0 (code 1); release signing via git-ignored `keystore.properties`; unsigned `bundleRelease` in CI
- [x] R8 release (1.45 MB APK / 3.6 MB AAB) installed on the emulator: forecast cache + preferences JSON work after shrinking
- [x] Store art from the character renderer (debug `--ez art true`): 512 icon, 1024×500 feature graphic, launcher foreground PNGs
- [x] 7 phone screenshots 1080×1920 (demo + fixed clock via debug extras `demo/clock/ask/travel/settings`; `AppClock`)
- [x] `PLAY_STORE.md`, `RELEASE_CHECKLIST.md`, `docs/gizlilik.html` (Android privacy policy) + `docs/index.html`
- [x] Fixes: travel default date and screen dates follow the app clock; a question asked before TTS is ready is no longer
      overwritten by the pending auto-briefing; location asks all providers at once (10 s budget instead of up to 24 s)

## Open
- [x] Privacy policy live: https://onurercan88.github.io/pofuduk-android/gizlilik.html (GitHub Pages, `docs/`)
- [ ] (you) Upload keystore, Play Console account/app, closed test (12 testers / 14 days)
- [ ] Travel abroad: Android Geocoder gives no time zone → Turkey-only search for now
- [ ] Alarms are cleared by the system on reboot/force-stop; future ones come back with the next refresh, a due one in that gap is lost
- [ ] Emulator location fix took ~20 s when the last fix was old; measure on a real phone (fused provider)
- [ ] Voice recognition itself not verified (emulator has no Turkish language pack / host mic) — check on a real phone
- [ ] Parser (same as iOS): "yolculuğa" is not read as travel (only "yolculuk"); consider stem matching on both platforms
- [ ] Not tested by hand yet: offline/failed screen, denied-permission → settings → resume path, Android 8–11 (requestSingleUpdate path)
- [ ] Cold start on the emulator took several seconds right after boot (Play updates running); measure on a real device in A2
- [ ] If the TTS engine restarts mid-briefing (e.g. engine update), speech stops and subtitles stay on the last sentence — consider rebinding/retry
- [ ] Phases A2–A7 (ROADMAP.md)
- [x] A6: WeatherKit proxy (`proxy/weatherkit-worker`, 9 Node tests) + Apple Weather provider/cross-check in core
      (25 tests), attribution UI; verified on the emulator against the Worker code with a fake Apple upstream
      (primary + fallback/cross-check paths)
- [x] A6 live (2026-11-20): Services ID `com.pofuduk.weather`, WeatherKit key, Worker `pofuduk-weatherkit.pofuduk.workers.dev`
      deployed with secrets; emulator shows live Apple Weather + attribution. Release bundles need `local.properties` set.
- [ ] Open-Meteo non-commercial confirmation (shared with iOS) — still needed while it is the fallback
