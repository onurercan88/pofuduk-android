# Pofuduk Android — Release checklist (1.0.0, Google Play, Turkey only)

Steps marked **(you)** need your accounts, passwords or decisions — Claude never enters them.

## 1. Upload key (you, once) ✅ created 2026-11-20 (`C:\Source\pofuduk-keys\pofuduk-upload.jks`, CN=Pofuduk)
Keep the keystore and its passwords **outside the repo** and back them up (losing the upload key needs a Play reset).
Easiest: `scripts\create-upload-key.ps1` (hidden password prompt, writes `keystore.properties`). Manual alternative:
```powershell
& "$env:USERPROFILE\Android\jdk\jdk-17.0.21.1+2\bin\keytool.exe" -genkeypair -v -keystore C:\Source\pofuduk-upload.jks -alias pofuduk-upload -keyalg RSA -keysize 4096 -validity 10000
```
Then create `C:\Source\PofudukAndroid\keystore.properties` (git-ignored):
```
storeFile=../pofuduk-upload.jks
storePassword=…
keyAlias=pofuduk-upload
keyPassword=…
```

## 2. Build the bundle
```powershell
scripts\gradle.cmd :core:test :app:bundleRelease
```
Output: `app\build\outputs\bundle\release\app-release.aab` (R8 on; ~3.6 MB bundle, ~1.5 MB install).
Before every release: bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.

## 3. Privacy policy page ✅ published 2026-11-20
`docs/gizlilik.html` + `docs/index.html` → GitHub Pages from `main` / `docs`:
```powershell
gh api -X POST repos/onurercan88/pofuduk-android/pages -f "source[branch]=main" -f "source[path]=/docs"
```
URL: https://onurercan88.github.io/pofuduk-android/gizlilik.html

## 4. Play Console (you)
Status 2026-11-23: personal developer account "Pofuduk Apps" verified (identity: address proof needed, ID card alone was
rejected; phone verified). App created: "Pofuduk: Konuşan Hava Durumu", `com.pofuduk.app`, Türkçe, App, Free,
Play App Signing terms accepted, automatic protection (installer check) on by default. App content declarations and
store listing (texts + graphics, AI label: none) saved. Internal testing release 1 (1.0.0) published to the email
list "Pofuduk iç test"; join link https://play.google.com/apps/internaltest/4700048428489224599.
Closed testing – Alpha set up (Türkiye only, same email list, feedback → GitHub issues, release 1 (1.0.0)); all 14
changes sent to Google for review 2026-11-23 (managed publishing off: goes live when approved). Tester opt-in:
https://play.google.com/apps/testing/com.pofuduk.app. Next: add ≥ 12 (better 15–20) testers to the list, 14 days.
1. Developer account (personal accounts: identity verification; new personal accounts must run a **closed test with ≥ 12
   testers for 14 days** before production access).
2. Create app: name, default language Türkçe, App, Free.
3. **Play App Signing**: accept (Google keeps the app signing key; you upload with the upload key).
4. Store listing: texts and graphics from `PLAY_STORE.md` / `docs/store/`.
5. App content: privacy policy URL, ads = no, app access = all features available, content rating (IARC),
   target audience (recommend 13+), data safety (proposal in `PLAY_STORE.md`).
6. Countries: Turkey only. Price: free.

## 5. Tests
1. **Internal testing**: upload the AAB, install via the Play link on a real phone. Check on device:
   location prompt flow, live forecast speed, Turkish TTS voice, **voice questions** (not verifiable on the emulator),
   notifications (rain/morning), "Verilerimi sil".
2. **Closed testing**: ≥ 12 testers opted in for 14 continuous days, then apply for production.
3. Pre-launch report (Play runs it automatically on the closed track): fix crashes/accessibility findings.

## 6. Production
Staged rollout (e.g. 20 % → 100 %), release notes from `PLAY_STORE.md`.

## Store graphics (regenerate when the UI changes)
Debug build only; run on the emulator (AVD `pofuduk35`):
```powershell
adb shell am start -n com.pofuduk.app/.MainActivity --ez art true
adb exec-out run-as com.pofuduk.app cat files/store-art/icon-512.png > docs\store\icon-512.png
adb exec-out run-as com.pofuduk.app cat files/store-art/feature-1024x500.png > docs\store\feature-1024x500.png
adb shell wm size 1080x1920
adb shell am start -n com.pofuduk.app/.MainActivity --es demo RAINY_AFTERNOON --es clock 2026-11-14T09:00 --es ask "'Yağmur saat kaçta başlayacak?'"
adb shell wm size reset
```
Other debug extras: `--es travel Bursa`, `--ez settings true`; dark mode via `adb shell cmd uimode night yes`.
Play screenshot rule: the long side may be at most twice the short side (hence 1080×1920, not the emulator's 1080×2400).

## Before release — open items
- [ ] WeatherKit proxy (recommended): Apple key + Services ID, Cloudflare deploy and secrets, then
      `pofuduk.weatherkitProxyUrl` / `pofuduk.weatherkitAppKey` in `local.properties` before `bundleRelease`
      (`proxy/weatherkit-worker/README.md`). Check the footer shows the Apple Weather mark.
- [ ] Open-Meteo (fallback, or primary without the proxy): confirm by e-mail that a free, ad-free app is non-commercial
      (draft in the iOS docs) or use an Open-Meteo API plan.
- [ ] Real-device checks listed under 5.1.
- [ ] Final launcher icon art (currently a simplified vector; the 512 px store icon uses the real character renderer).
