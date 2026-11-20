package com.pofuduk.core.datetime

import com.pofuduk.core.conversation.DateReference
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.util.foundationWeekday
import com.pofuduk.core.util.isSameDay
import com.pofuduk.core.util.localDate
import com.pofuduk.core.util.startInstant
import java.time.Instant
import java.time.ZoneId

/** Resolves date references to local days in the place's time zone (DST- and midnight-safe). */
class ForecastDateResolver {
    /** Start-of-day instants for the reference, in [zone]. */
    fun days(reference: DateReference, now: Instant, zone: ZoneId): List<Instant> {
        val today = now.localDate(zone)
        fun plus(n: Int): Instant = today.plusDays(n.toLong()).startInstant(zone)

        return when (reference) {
            DateReference.Today -> listOf(plus(0))
            DateReference.Tomorrow -> listOf(plus(1))
            is DateReference.InDays -> listOf(plus(maxOf(0, reference.days)))
            is DateReference.Weekday -> listOf(plus((reference.weekday - today.foundationWeekday + 7) % 7))
            DateReference.Weekend -> when (val w = today.foundationWeekday) {
                7 -> listOf(plus(0), plus(1)) // Saturday
                1 -> listOf(plus(0)) // Sunday
                else -> listOf(plus(7 - w), plus(8 - w))
            }
        }
    }

    /** The subset of [days] the snapshot actually covers (daily entry or at least one hourly entry). */
    fun covered(days: List<Instant>, snapshot: WeatherSnapshot): List<Instant> {
        val zone = snapshot.place.timeZone
        return days.filter { day ->
            snapshot.daily.any { it.date.isSameDay(day, zone) } || snapshot.hourly.any { it.time.isSameDay(day, zone) }
        }
    }
}
