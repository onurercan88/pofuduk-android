# Pofuduk Android — Privacy

Same principles as iOS: **no accounts, no ads, no analytics, no data collection, no sale of data.**

| Data | When | Why | Stored | Shared |
|---|---|---|---|---|
| Location (coarse first, precise optional) | Only in the foreground when a forecast is needed, after Pofuduk's explanation (Phase A2) | Local forecast | Last selected place, coarsened to ~1 km (2 decimals) | Rounded coordinates to the weather provider only |
| Microphone / speech | Only while the mic button is held (Phase A3) | Voice questions | Not stored; offline recognition preferred | Google's recognizer only when offline recognition is unavailable (user is told) |
| Notifications | Only if enabled (Phase A4) | Rain/warning/morning alerts | Preferences + delivery log on device | None |
| Preferences, cached forecasts | Always | App function | On device (SharedPreferences, cache dir) | None |

- Phase A1 (current): demo data only, **no permissions requested**, no network access.
- No background location, no continuous microphone.
- Cloud backup and device transfer are disabled for all app data (`data_extraction_rules.xml`).
- TextToSpeech: an installed offline Turkish voice is preferred; network voices are not selected.
- "Delete my data" will clear cache, history and saved places (Phase A5).
- Play "Data safety": no data collected or shared. Privacy policy: reuse the Turkish iOS policy page, adding Android specifics.
