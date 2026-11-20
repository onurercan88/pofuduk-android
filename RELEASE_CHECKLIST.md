# Pofuduk Android — Release checklist (1.0.0, Google Play, Turkey only)

Steps marked **(you)** need your accounts, passwords or decisions — Claude never enters them.

## 1. Upload key (you, once)
Keep the keystore and its passwords **outside the repo** and back them up (losing the upload key needs a Play reset).
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
- [ ] Open-Meteo: confirm by e-mail that a free, ad-free app is non-commercial (draft in the iOS docs), or move to the
      WeatherKit proxy (ROADMAP A6) / Open-Meteo API plan.
- [ ] Real-device checks listed under 5.1.
- [ ] Final launcher icon art (currently a simplified vector; the 512 px store icon uses the real character renderer).
