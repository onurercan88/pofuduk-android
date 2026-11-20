# Pofuduk Android — Architecture

## Overview
```
core/      Pure Kotlin/JVM library (no Android imports). Port of iOS PofudukCore + its tests.
  model/          Weather, place, alert, character, preference models (immutable, kotlinx.serialization)
  engine/         WeatherSummary, CharacterPersonality, ClothingRecommendation, TravelWeatherSummary,
                  LocationResolver, WeatherProviderFallback, WeatherThresholds
  conversation/   WeatherQueryParser, WeatherIntentResolver, ConversationManager, WeatherAnswerEngine
  notification/   RainNotificationEngine, MorningBriefingEngine, preferences + delivery log
  speech/         SentenceSegmenter, SubtitleTrack (immutable), LipSync
  alert/          CAPWarningParser (DOCTYPE rejected), WeatherAlertMatcher, MGM provider/repository
  provider/       WeatherProvider contracts, HttpClient (java.net), OpenMeteoProvider
  repository/     CachedWeatherRepository + cache stores (memory, JSON files)
  data/           TurkishProvinces (81 provinces, offline)
  datetime/       TurkishFormatting/Grammar, LocalTimeParser, ForecastDateResolver
  store/          KeyValueStore (Android: SharedPreferences adapter, tests: in memory)
  mock/           MockWeatherData — previews, tests, labelled demo mode only
app/       Android app (Jetpack Compose, single activity, MVVM)
  speech/         SpeechService — TextToSpeech tr-TR adapter
  location/       DeviceLocationProvider (LocationManager + Geocoder, coarse only) + LocationService (core policy)
  data/           SharedPreferencesStore (KeyValueStore)
  home/           HomeViewModel (load state machine, StateFlow) + HomeScreen + LocationViews
  character/      CharacterView, CharacterFigure, WeatherEffects (Compose Canvas)
  ui/theme/       Palette (light/dark/night), gradients
```

## Key decisions
| Decision | Why |
|---|---|
| Kotlin + Jetpack Compose, minSdk 26, target/compile 36, Gradle Kotlin DSL + version catalog | Modern default; API 26 has `UtteranceProgressListener.onRangeStart` (word sync) and adaptive icons. |
| Business logic in a JVM `core` module, ported 1:1 from iOS with its tests | Fast tests on Windows without emulator; identical wording/thresholds on both platforms. |
| `applicationId` = `com.pofuduk.app` (same as iOS bundle ID) | Consistent identity; cannot change after Play release. |
| Forecast: Open-Meteo first; WeatherKit REST via a small backend proxy later | WeatherKit has no Android SDK and its JWT key must not ship in the APK (see API_INTEGRATIONS.md). |
| No third-party networking/JSON libs beyond kotlinx.serialization; HTTP via `java.net` | Smaller APK, fewer dependencies; works on JVM tests and Android. |
| One TTS utterance per sentence, ids `session:index` | `onStart` = exact sentence sync, `onRangeStart` = word highlight + lip sync. Subtitles are the spoken strings — no timing estimates. Stale sessions are ignored. |
| Character drawn with Compose Canvas in a 200×200 design space | Port of the iOS SwiftUI shapes; no assets; respects "Remove animations" (frozen still frame). Swap point: `CharacterView(appearance)`. |
| `SubtitleTrack` is immutable (returns new values) | Safe as Compose state; TTS callbacks update a `StateFlow` atomically. |
| No cloud backup (`allowBackup=false`, data extraction rules exclude all) | Privacy: nothing leaves the device. |
| Permissions added per phase | Phase 1 (demo) requests none. |

## iOS → Android mapping
| iOS | Android |
|---|---|
| AVSpeechSynthesizer (`didStart`, `willSpeakRange`) | TextToSpeech + UtteranceProgressListener (`onStart`, `onRangeStart`) |
| SFSpeechRecognizer | SpeechRecognizer (`EXTRA_PREFER_OFFLINE`, tr-TR) — Phase A3 |
| CoreLocation When-In-Use | Fused/LocationManager, coarse-first, foreground only — Phase A2 |
| BGAppRefreshTask + UNUserNotificationCenter | WorkManager periodic work + NotificationManager channels — Phase A4 |
| UserDefaults | SharedPreferences via `KeyValueStore` |
| Caches directory JSON | `FileWeatherCacheStore(context.cacheDir)` |
| WeatherKit | `proxy/weatherkit-worker` (Cloudflare Worker, WeatherKit REST) + `WeatherKitProxyProvider` |

## Data flow (Home)
```
HomeViewModel ──► (Phase 1: MockWeatherData | Phase A2: CachedWeatherRepository) ──► WeatherSnapshot
      ├──► WeatherSummaryEngine.analyze ──► WeatherAnalysis ──► CharacterPersonalityEngine.briefing
      ├──► ClothingRecommendationEngine
      └──► SpeechService.speak(sentences) ──callbacks──► SubtitleTrack + SpokenWordCue (lip sync)
```

## Rules
- No networking, business logic or speech in composables.
- Every external integration sits behind an interface in `core`.
- Every snapshot carries source, retrieval time and expiry; demo/stale data is always labelled.
