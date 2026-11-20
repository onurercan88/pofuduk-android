package com.pofuduk.core.datetime

import com.pofuduk.core.util.localInstant
import java.time.Instant
import java.time.ZoneId

/** Parses provider local-time strings in the location's time zone (DST-correct, locale-independent). */
object LocalTimeParser {
    /** "2026-11-14T09:00" or "2026-11-14 09:00" (anything after minutes is ignored). */
    fun dateTime(string: String, zone: ZoneId): Instant? {
        val parts = string.split('-', 'T', ' ', ':').filter { it.isNotEmpty() }.take(5).mapNotNull { it.toIntOrNull() }
        if (parts.size != 5) return null
        return make(parts[0], parts[1], parts[2], parts[3], parts[4], zone)
    }

    /** "2026-11-14" → local midnight. */
    fun day(string: String, zone: ZoneId): Instant? {
        val parts = string.split('-').filter { it.isNotEmpty() }.take(3).mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        return make(parts[0], parts[1], parts[2], 0, 0, zone)
    }

    private fun make(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId): Instant? =
        runCatching { localInstant(y, mo, d, h, mi, zone) }.getOrNull()
}
