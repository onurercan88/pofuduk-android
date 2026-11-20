package com.pofuduk.core.conversation

import com.pofuduk.core.data.TurkishProvinces

sealed interface DateReference {
    data object Today : DateReference
    data object Tomorrow : DateReference
    data class InDays(val days: Int) : DateReference
    data object Weekend : DateReference
    /** Foundation weekday (1 = Sunday … 7 = Saturday); the next occurrence, today included. */
    data class Weekday(val weekday: Int) : DateReference
}

sealed interface DayPart {
    /** Local hours covered (inclusive). */
    val hours: IntRange

    data object Morning : DayPart { override val hours = 6..11 }
    data object Noon : DayPart { override val hours = 11..14 }
    data object Afternoon : DayPart { override val hours = 12..17 }
    data object Evening : DayPart { override val hours = 18..22 }
    data object Night : DayPart { override val hours = 21..23 }
    data class Hour(val hour: Int) : DayPart { override val hours get() = hour..hour }
}

enum class WeatherTopic { OVERVIEW, RAIN, RAIN_TIMING, UMBRELLA, TEMPERATURE, WIND, UV, CLOTHING, GOING_OUT, TRAVEL }

/** Parsed from one utterance. Null fields mean "not mentioned" (may be inherited from context). */
data class WeatherIntent(
    val topic: WeatherTopic,
    val date: DateReference? = null,
    val dayPart: DayPart? = null,
    /** Province mentioned in the query (resolved against the offline province list). */
    val province: TurkishProvinces.Province? = null,
    val isFollowUp: Boolean = false,
)

/** Intent after merging with conversation context; ready to be answered. */
data class ResolvedQuery(
    val topic: WeatherTopic,
    val date: DateReference,
    val dayPart: DayPart? = null,
    /** null = the user's current place. */
    val province: TurkishProvinces.Province? = null,
)
