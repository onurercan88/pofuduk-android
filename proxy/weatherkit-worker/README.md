# Pofuduk WeatherKit proxy (Cloudflare Worker)

Android has no WeatherKit SDK, and the WeatherKit REST API needs a JWT signed with a private `.p8` key that must not ship
inside the APK. This Worker holds the key, signs the requests and returns a trimmed WeatherKit response to the app.

- `GET /v1/weather?lat&lon&tz&start` → current + hourly + daily for 7 days from `start` (the place's local midnight).
- `GET /v1/current?lat&lon&tz` → current conditions only (cross-check of "now" when the app fell back to Open-Meteo).
- Optional header `X-Pofuduk-Key` when the `APP_KEY` secret is set. It only deters casual use (an APK can be unpacked);
  the per-IP rate limit and the Apple quota are the real limits.
- Coordinates are rounded to 2 decimals (~1 km), which is also the cache cell (30 min forecast, 10 min current; in memory
  per isolate, plus the Cloudflare edge cache when the Worker runs on a custom domain — it is a no-op on `*.workers.dev`).
- No storage, no request logs (`observability.enabled = false`), never logs coordinates or IPs. Upstream errors are logged
  as status codes only.

## Setup (you — needs your Apple and Cloudflare accounts)
1. **Apple Developer** → Certificates, Identifiers & Profiles:
   - Identifiers → **+** → *Services IDs* → e.g. `com.pofuduk.weather` (this is `SERVICE_ID`).
   - Keys → **+** → enable **WeatherKit** → download `AuthKey_XXXXXXXXXX.p8` (only once; keep it outside the repo).
     The 10-character Key ID is `KEY_ID`.
   - Membership → Team ID (`TEAM_ID`). The Apple Developer Program includes 500,000 WeatherKit calls/month.
2. **Cloudflare** (free plan): create an account, then in this folder:
   ```powershell
   npx wrangler@4 login
   npx wrangler@4 deploy
   ```
   The output shows the URL, e.g. `https://pofuduk-weatherkit.<account>.workers.dev`.
3. **Secrets** (prompted, never written to a file):
   ```powershell
   npx wrangler@4 secret put TEAM_ID
   npx wrangler@4 secret put KEY_ID
   npx wrangler@4 secret put SERVICE_ID
   Get-Content C:\path\to\AuthKey_XXXXXXXXXX.p8 -Raw | npx wrangler@4 secret put PRIVATE_KEY
   npx wrangler@4 secret put APP_KEY
   ```
   A random app key: `$b = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`
4. **Check**:
   ```powershell
   curl.exe -H "X-Pofuduk-Key: <APP_KEY>" "https://pofuduk-weatherkit.<account>.workers.dev/v1/current?lat=41.01&lon=28.98&tz=Europe/Istanbul"
   ```
   `{"currentWeather":{…}}` = working; `500 not_configured` = a secret is missing; `502` = Apple rejected the token
   (check IDs / key).
5. **App**: add to `C:\Source\PofudukAndroid\local.properties` (git-ignored), then rebuild:
   ```
   pofuduk.weatherkitProxyUrl=https://pofuduk-weatherkit.<account>.workers.dev
   pofuduk.weatherkitAppKey=<APP_KEY>
   ```
   Without these the app uses Open-Meteo only (CI builds).

Optional: a custom domain for the Worker enables the edge cache; the rate limit (`[[ratelimits]]` in `wrangler.toml`,
60 requests/min per IP) can be tuned there.

## Local development
```powershell
npm test                      # node --test (Node 18+), no Cloudflare account needed
npx wrangler@4 dev            # http://localhost:8787 (secrets from a git-ignored .dev.vars file)
adb reverse tcp:8787 tcp:8787 # emulator → local Worker; debug builds allow plain HTTP to localhost only
```
Build the debug app against it with `-Ppofuduk.weatherkitProxyUrl=http://localhost:8787`.
