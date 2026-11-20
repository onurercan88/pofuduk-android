# Pofuduk Android — CLAUDE.md

Android port of Pofuduk (Turkish-speaking cloud weather character). The iOS app lives in a separate repo
(`C:\Source\WeatherTalking`, GitHub `onurercan88/pofuduk`) and is **reference only** — never edit it from here.

Details live in:
- `ARCHITECTURE.md` — modules, decisions, iOS → Android mapping
- `ROADMAP.md` — phases
- `PROJECT_STATUS.md` — progress summary (update only changed items)
- `API_INTEGRATIONS.md` — data sources and licences
- `PRIVACY.md` — data handling (same principles as iOS)

Product requirements: the iOS repo's `PRD.md` (read the relevant section only).

## Build (Windows dev machine)
- Toolchain lives outside AppData (the desktop app virtualises AppData writes):
  JDK 17 `%USERPROFILE%\Android\jdk\jdk-17.0.21.1+2`, SDK `%USERPROFILE%\Android\Sdk`.
- `scripts\gradle.cmd :core:test` — pure-Kotlin business logic tests (JVM, fast).
- `scripts\gradle.cmd :app:assembleDebug` — debug APK (`app\build\outputs\apk\debug\`).
- Live Open-Meteo check (opt-in): `$env:POFUDUK_LIVE_TESTS='1'; scripts\gradle.cmd :core:test`.
- Gradle/SDK commands need the sandbox disabled (they write to `%USERPROFILE%\.gradle` and the SDK).
- `git push` only works in the user's terminal tabs (sandbox can't read GitHub credentials).
- Put new business logic in `core` (pure Kotlin/JVM, no Android imports) so it stays testable.

## Port rules
- `core` mirrors iOS `PofudukCore` 1:1 (same Turkish strings, same thresholds, same tests). When iOS logic
  changes, port the change and its test; keep behaviour identical unless a platform reason is documented.
- Swift `rounded()` = half away from zero → use `roundedInt()/roundedHalfAway()` (never `roundToInt`).
- Swift `Date`/`Calendar` → `Instant` + `ZoneId` helpers in `core/util/TimeSupport.kt`.
  Foundation weekday numbering (1 = Sunday) is kept for `DateReference.Weekday`.
- Models are immutable data classes; mutate with `copy()`.

## Working rules (mandatory)

**Tokens & responses**
- Concise, actionable output. No repeated summaries, no obvious explanations, no unrequested docs.
- Answer the user in Turkish.

**Code reading**
- Read only task-relevant files. Search before opening large files.
- Skip generated files, dependencies, build outputs, lockfiles. Don't re-read what's in context.

**Implementation**
- One clearly defined task at a time. No unrelated refactors or changes to working features.
- Reuse existing architecture/components. No new dependencies without clear reason.
- Don't implement future-phase features early.

**Commands**
- Filter output to relevant errors/warnings; no full build logs.
- Targeted tests after small changes; full suite at milestones.

**Context**
- Keep `PROJECT_STATUS.md` short and current. Record essential decisions in docs.

**Accounts & secrets**
- The user enters all account data (Google Play Console, signing keys, API keys) themselves.
- Never commit keystores, `keystore.properties`, `local.properties` or API keys.

**End-of-task report**
1. Implemented
2. Modified files
3. Tests and build results
4. Remaining issues

**Primary rule:** never sacrifice correctness, security, testing or maintainability to save tokens.
