package com.pofuduk.core.model

import java.time.Instant

/** MGM warning levels. Each level has a text label so severity is never shown by colour alone. */
enum class WarningLevel {
    GREEN, YELLOW, ORANGE, RED;

    val turkishName: String
        get() = when (this) {
            GREEN -> "yeşil"
            YELLOW -> "sarı"
            ORANGE -> "turuncu"
            RED -> "kırmızı"
        }
}

/** An official warning. Only an authorized official source may create one; never derived from a forecast. */
data class OfficialWarning(
    val id: String,
    val level: WarningLevel,
    val hazard: String,
    val description: String,
    val provinces: List<String>,
    val districts: List<String> = emptyList(),
    val validFrom: Instant,
    val validUntil: Instant,
    val sourceUrl: String,
) {
    fun isActive(at: Instant): Boolean = !at.isBefore(validFrom) && at.isBefore(validUntil)
}

/**
 * Result of an MGM warning lookup. [Unavailable] means automated data could not be obtained,
 * which is different from "there are no warnings".
 */
sealed interface WarningAvailability {
    data class Available(val warnings: List<OfficialWarning>) : WarningAvailability
    data class Unavailable(val reason: String, val officialPage: String) : WarningAvailability
}
