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

## Open
- [ ] Emulator/device run + screenshot (needs emulator + system image download, or a USB device)
- [ ] GitHub repo (`onurercan88/pofuduk-android`?) — ask the user before creating; push from the user's terminal
- [ ] CI (GitHub Actions: `:core:test` + `:app:assembleDebug`)
- [ ] Phases A2–A7 (ROADMAP.md)
- [ ] Open-Meteo non-commercial confirmation (shared with iOS) or WeatherKit proxy before Play release
