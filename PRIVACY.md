# Pofuduk Android — Privacy

Same principles as iOS: **no accounts, no ads, no analytics, no data collection, no sale of data.**

| Data | When | Why | Stored | Shared |
|---|---|---|---|---|
| Approximate location (`ACCESS_COARSE_LOCATION` only) | Only in the foreground when a forecast is needed, after Pofuduk's explanation | Local forecast | Last place, coarsened to ~1 km (2 decimals) | Coordinates rounded to 2 decimals (~1 km) to Open-Meteo and to the platform geocoder (place name) |
| Place search text | Only while the user types in the picker (≥ 2 letters) | District search | Not stored | The platform geocoder (Google on most devices); the 81 provinces work offline |
| Microphone / speech | Only after a mic tap, until the question ends (≤ 10 s) | Voice questions | Not stored | On-device recognition when available; otherwise the device's recognition service (usually Google) — the user is told on screen |
| Notifications | Only if enabled (Phase A4) | Rain/warning/morning alerts | Preferences + delivery log on device | None |
| Preferences, cached forecasts | Always | App function | On device (SharedPreferences, cache dir) | None |

- Permissions today: `INTERNET`, `ACCESS_COARSE_LOCATION` (asked only after the explanation; picking a city works without it),
  `RECORD_AUDIO` (asked on the first mic tap; typing works without it).
- Logs contain timings only — never coordinates or place names.
- No background location, no continuous microphone.
- Cloud backup and device transfer are disabled for all app data (`data_extraction_rules.xml`).
- TextToSpeech: an installed offline Turkish voice is preferred; network voices are not selected.
- "Delete my data" will clear cache, history and saved places (Phase A5).
- Play "Data safety": no data collected or shared. Privacy policy: reuse the Turkish iOS policy page, adding Android specifics.
