package com.pofuduk.app

import java.time.Instant

/**
 * The app's notion of "now". Always the real clock in release builds; debug builds may fix it for
 * reproducible store screenshots (`--es clock 2026-11-14T09:00`, see RELEASE_CHECKLIST.md).
 */
object AppClock {
    @Volatile var now: () -> Instant = { Instant.now() }
}
