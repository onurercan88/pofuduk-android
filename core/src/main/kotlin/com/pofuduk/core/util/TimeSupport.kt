package com.pofuduk.core.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor

/** Turkish locale for case mapping ("I" → "ı", "İ" → "i"). */
val TurkishLocale: Locale = Locale.forLanguageTag("tr-TR")

/** Swift's `rounded()`: half away from zero (Kotlin's `round` is half-even, `roundToInt` half-up). */
fun Double.roundedHalfAway(): Double = if (this >= 0) floor(this + 0.5) else -floor(-this + 0.5)

fun Double.roundedInt(): Int = roundedHalfAway().toInt()

/** Fractional seconds, like Swift's `addingTimeInterval`. */
fun Instant.plusSeconds(seconds: Double): Instant = plusNanos((seconds * 1_000_000_000.0).toLong())

/** Seconds from [other] to this instant (positive when this is later). */
fun Instant.secondsSince(other: Instant): Double = Duration.between(other, this).toNanos() / 1_000_000_000.0

val Instant.epochSecondsDouble: Double get() = epochSecond + nano / 1_000_000_000.0

fun Instant.zoned(zone: ZoneId): ZonedDateTime = atZone(zone)
fun Instant.localDate(zone: ZoneId): LocalDate = atZone(zone).toLocalDate()
fun Instant.hour(zone: ZoneId): Int = atZone(zone).hour

/** Start of the clock hour containing this instant, in [zone]. */
fun Instant.startOfHour(zone: ZoneId): Instant = atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()

/** Local midnight of the day containing this instant, in [zone] (DST-safe). */
fun Instant.startOfDay(zone: ZoneId): Instant = localDate(zone).atStartOfDay(zone).toInstant()

/** Start of the next local day. */
fun Instant.endOfDay(zone: ZoneId): Instant = localDate(zone).plusDays(1).atStartOfDay(zone).toInstant()

fun Instant.isSameDay(other: Instant, zone: ZoneId): Boolean = localDate(zone) == other.localDate(zone)

fun LocalDate.startInstant(zone: ZoneId): Instant = atStartOfDay(zone).toInstant()

/** Swift/Foundation weekday numbering: 1 = Sunday … 7 = Saturday. */
val LocalDate.foundationWeekday: Int get() = dayOfWeek.value % 7 + 1
fun Instant.foundationWeekday(zone: ZoneId): Int = localDate(zone).foundationWeekday

/** Local date-time in [zone] as an instant (gaps resolve forward, like Foundation). */
fun localInstant(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId): Instant =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant()

fun String.capitalizedFirstTurkish(): String =
    if (isEmpty()) this else substring(0, 1).uppercase(TurkishLocale) + substring(1)
