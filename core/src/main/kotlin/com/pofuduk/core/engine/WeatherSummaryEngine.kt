package com.pofuduk.core.engine

import com.pofuduk.core.model.CurrentWeather
import com.pofuduk.core.model.DailyForecast
import com.pofuduk.core.model.HourlyForecast
import com.pofuduk.core.model.Place
import com.pofuduk.core.model.WeatherCondition
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.model.WeatherTheme
import com.pofuduk.core.util.endOfDay
import com.pofuduk.core.util.hour
import com.pofuduk.core.util.isSameDay
import com.pofuduk.core.util.localDate
import com.pofuduk.core.util.roundedInt
import com.pofuduk.core.util.secondsSince
import com.pofuduk.core.util.startOfDay
import com.pofuduk.core.util.startOfHour
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

enum class PrecipitationKind { RAIN, SNOW, SLEET, THUNDERSTORM }

enum class PrecipitationIntensity {
    /** Amount data missing and condition not specific → never call it heavy. */
    UNKNOWN, LIGHT, MODERATE, HEAVY
}

data class PrecipitationOutlook(
    val kind: PrecipitationKind,
    val start: Instant,
    /** Exclusive end of the last wet hour in the first continuous wet period. */
    val end: Instant,
    /** The period reaches the end of today's analysed window (it may continue beyond). */
    val lastsUntilEndOfDay: Boolean,
    val intensity: PrecipitationIntensity,
    val maxProbability: Int?,
    val totalMm: Double?,
    /** true = at or above the "likely" threshold; false = only "possible" (communicate uncertainty). */
    val isLikely: Boolean,
) {
    val durationHours: Int get() = max(1, (end.secondsSince(start) / 3600).roundedInt())
}

/**
 * It is precipitating now, but the forecast's first wet period starts at a later hour (the forecast missed the
 * current shower). Decides how the later period is told, so the briefing never says
 * "yağmur saat 9'da başlayacak" while it is already raining.
 */
enum class LaterPrecipitationRelation {
    /** No real break is forecast: it is the same precipitation going on. */
    CONTINUES,
    /** It gets heavier (or turns into a thunderstorm) from the period's start. */
    INTENSIFIES,
    /** At least two dry hours are forecast first: it may stop and start again. */
    RESUMES,
}

data class WindOutlook(val maxSpeedKmh: Double, val maxGustKmh: Double?, val isStrong: Boolean)

/** Part of the day the briefing is given in. Decides which hours are worth talking about. */
enum class BriefingTime {
    /** 00.00–12.59: the whole day is still ahead. */
    MORNING,
    /** 13.00–16.59: talk about the rest of the day. */
    AFTERNOON,
    /** 17.00–21.59: talk about tonight and tomorrow. */
    EVENING,
    /** 22.00–23.59: talk about the night and tomorrow. */
    NIGHT;

    val looksAhead: Boolean get() = this == EVENING || this == NIGHT

    companion object {
        fun of(hour: Int): BriefingTime = when (hour) {
            in 13..16 -> AFTERNOON
            in 17..21 -> EVENING
            in 22..23 -> NIGHT
            else -> MORNING
        }
    }
}

data class TomorrowOutlook(
    val low: Double,
    val high: Double,
    val condition: WeatherCondition,
    val precipitationProbability: Int?,
    /** At or above the "likely" threshold (with an amount, if the source gives one). */
    val precipitationLikely: Boolean,
    /** Between the "possible" and "likely" thresholds. */
    val precipitationPossible: Boolean,
)

data class WeatherAnalysis(
    val place: Place,
    val source: WeatherDataSource,
    val analyzedAt: Instant,
    val isStale: Boolean,
    val retrievedAt: Instant,
    val current: CurrentWeather,
    val minTemperature: Double?,
    val maxTemperature: Double?,
    val feelsLikeIsNotable: Boolean,
    val temperatureSwingIsNotable: Boolean,
    /** First continuous precipitation period in the rest of today, if any. */
    val precipitation: PrecipitationOutlook?,
    /** Set when it is precipitating now and [precipitation] starts at a later hour. */
    val laterPrecipitation: LaterPrecipitationRelation?,
    /** Hourly data covers the rest of today, so "no rain expected" can be stated. */
    val hasHourlyCoverage: Boolean,
    val wind: WindOutlook?,
    val uvMax: Double?,
    val isHighUV: Boolean,
    val briefingTime: BriefingTime,
    /** Lowest / highest temperature from the current hour to the end of today. */
    val restOfDayLow: Double?,
    val restOfDayHigh: Double?,
    /** Lowest temperature from now until 07.00 tomorrow (evening and night briefings). */
    val tonightLow: Double?,
    val tomorrow: TomorrowOutlook?,
    /** Character look: the weather now (and precipitation starting within the hour). */
    val theme: WeatherTheme,
    /** The rest of today (precipitation at any later hour counts). Drives wording, not the look. */
    val outlookTheme: WeatherTheme,
)

/** Turns a snapshot into facts. Produces no wording; that is the personality engine's job. */
class WeatherSummaryEngine(val thresholds: WeatherThresholds = WeatherThresholds.DEFAULT) {

    fun analyze(snapshot: WeatherSnapshot, now: Instant): WeatherAnalysis {
        val zone = snapshot.place.timeZone
        val hourStart = now.startOfHour(zone)
        val dayStart = now.startOfDay(zone)
        val dayEnd = now.endOfDay(zone)
        val remaining = snapshot.hourly.filter { !it.time.isBefore(hourStart) && it.time.isBefore(dayEnd) }

        val today = snapshot.daily.firstOrNull { it.date.isSameDay(now, zone) }
        val todayHours = snapshot.hourly.filter { !it.time.isBefore(dayStart) && it.time.isBefore(dayEnd) }
        val minT = today?.minTemperature ?: todayHours.minOfOrNull { it.temperature }
        val maxT = today?.maxTemperature ?: todayHours.maxOfOrNull { it.temperature }

        val precipitation = precipitationOutlook(remaining, dayEnd)
        val wind = windOutlook(snapshot.current, remaining)
        val uvValues = remaining.mapNotNull { it.uvIndex } + listOfNotNull(snapshot.current.uvIndex, today?.uvIndexMax)
        val uvMax = uvValues.maxOrNull()

        val current = snapshot.current
        val feelsNotable = abs(current.feelsLike - current.temperature) >= thresholds.notableFeelsLikeDifference
        val swingNotable = minT != null && maxT != null && maxT - minT >= thresholds.notableTemperatureSwing

        // Coverage: hourly data reaches at least the last hour of today.
        val lastHourOfDay = dayEnd.minusSeconds(3600)
        val coverage = remaining.isNotEmpty() && !remaining.last().time.isBefore(lastHourOfDay)

        val theme = theme(current, precipitation, wind, now)
        val outlookTheme = theme(current, precipitation, wind, now, leadTimeSeconds = Double.POSITIVE_INFINITY)

        val briefingTime = BriefingTime.of(now.hour(zone))
        val remainingTemps = remaining.map { it.temperature } + current.temperature
        val tonightEnd = dayEnd.localDate(zone).atTime(7, 0).atZone(zone).toInstant()
        val tonightTemps = snapshot.hourly.filter { !it.time.isBefore(hourStart) && it.time.isBefore(tonightEnd) }
            .map { it.temperature } + current.temperature
        val tomorrow = tomorrowOutlook(snapshot.daily.firstOrNull { it.date.isSameDay(dayEnd, zone) })

        return WeatherAnalysis(
            place = snapshot.place,
            source = snapshot.source,
            analyzedAt = now,
            isStale = snapshot.isStale(now),
            retrievedAt = snapshot.retrievedAt,
            current = current,
            minTemperature = minT,
            maxTemperature = maxT,
            feelsLikeIsNotable = feelsNotable,
            temperatureSwingIsNotable = swingNotable,
            precipitation = precipitation,
            laterPrecipitation = laterPrecipitationRelation(current, precipitation, remaining, hourStart),
            hasHourlyCoverage = coverage,
            wind = wind,
            uvMax = uvMax,
            isHighUV = (uvMax ?: 0.0) >= thresholds.highUVIndex,
            briefingTime = briefingTime,
            restOfDayLow = if (remaining.isEmpty()) null else remainingTemps.min(),
            restOfDayHigh = if (remaining.isEmpty()) null else remainingTemps.max(),
            tonightLow = if (tonightTemps.size > 1) tonightTemps.min() else null,
            tomorrow = tomorrow,
            theme = theme,
            outlookTheme = outlookTheme,
        )
    }

    // Tomorrow

    internal fun tomorrowOutlook(day: DailyForecast?): TomorrowOutlook? {
        if (day == null) return null
        val p = day.precipitationProbability
            ?: if (day.condition.isPrecipitation) thresholds.likelyPrecipitationProbability else 0
        // Amount given and ~0 → the probability alone does not make it a wet day.
        val dryByAmount = (day.precipitationSumMm?.let { it < thresholds.minimumWetHourMm } ?: false) &&
            !day.condition.isPrecipitation
        val likely = !dryByAmount && p >= thresholds.likelyPrecipitationProbability
        val possible = !dryByAmount && !likely && p >= thresholds.possiblePrecipitationProbability
        return TomorrowOutlook(
            low = day.minTemperature, high = day.maxTemperature, condition = day.condition,
            precipitationProbability = day.precipitationProbability,
            precipitationLikely = likely, precipitationPossible = possible,
        )
    }

    // Precipitation

    internal fun effectiveProbability(hour: HourlyForecast): Int {
        hour.precipitationProbability?.let { return it }
        // No probability given: trust an explicit precipitation condition or amount from the source.
        if (hour.condition.isPrecipitation || (hour.precipitationMm ?: 0.0) >= thresholds.minimumWetHourMm) {
            return thresholds.likelyPrecipitationProbability
        }
        return 0
    }

    internal fun isWetCandidate(hour: HourlyForecast): Boolean {
        if (effectiveProbability(hour) < thresholds.possiblePrecipitationProbability) return false
        // If the source gives an amount of ~0 and no precipitation condition, ignore the probability.
        val mm = hour.precipitationMm
        if (mm != null && mm < thresholds.minimumWetHourMm && !hour.condition.isPrecipitation) return false
        return true
    }

    internal fun isLikelyWet(hour: HourlyForecast): Boolean =
        isWetCandidate(hour) && effectiveProbability(hour) >= thresholds.likelyPrecipitationProbability

    /** First continuous run of hours matching [predicate]. */
    internal fun firstRun(hours: List<HourlyForecast>, predicate: (HourlyForecast) -> Boolean): List<HourlyForecast> {
        val firstIndex = hours.indexOfFirst(predicate)
        if (firstIndex < 0) return emptyList()
        val run = mutableListOf<HourlyForecast>()
        for (hour in hours.subList(firstIndex, hours.size)) {
            if (!predicate(hour)) break
            val last = run.lastOrNull()
            if (last != null && hour.time.secondsSince(last.time) > 3600) break
            run.add(hour)
        }
        return run
    }

    /**
     * Prefers the first *likely* period; only if none exists reports a *possible* period.
     * Possible hours are never merged into a likely period (that would overstate duration).
     */
    fun precipitationOutlook(hours: List<HourlyForecast>, dayEnd: Instant? = null): PrecipitationOutlook? {
        var run = firstRun(hours, ::isLikelyWet)
        if (run.isEmpty()) run = firstRun(hours, ::isWetCandidate)
        if (run.isEmpty()) return null

        val maxProbability = run.mapNotNull { it.precipitationProbability }.maxOrNull()
        val effectiveMax = run.maxOf { effectiveProbability(it) }
        val amounts = run.mapNotNull { it.precipitationMm }
        val total = if (amounts.isEmpty()) null else amounts.sum()

        val kind = when {
            run.any { it.condition == WeatherCondition.THUNDERSTORM } -> PrecipitationKind.THUNDERSTORM
            run.count { it.condition == WeatherCondition.SNOW } * 2 > run.size -> PrecipitationKind.SNOW
            run.any { it.condition == WeatherCondition.SLEET } -> PrecipitationKind.SLEET
            else -> PrecipitationKind.RAIN
        }

        val end = run.last().time.plusSeconds(3600)
        return PrecipitationOutlook(
            kind = kind,
            start = run.first().time,
            end = end,
            lastsUntilEndOfDay = dayEnd?.let { !end.isBefore(it) } ?: false,
            intensity = intensity(run, amounts.maxOrNull()),
            maxProbability = maxProbability,
            totalMm = total,
            isLikely = effectiveMax >= thresholds.likelyPrecipitationProbability,
        )
    }

    /**
     * See [LaterPrecipitationRelation]. null unless it is precipitating now and the outlook starts after the
     * current hour. The forecast already missed the current precipitation, so a single dry hour is not trusted
     * as a break; two or more are.
     */
    fun laterPrecipitationRelation(
        current: CurrentWeather,
        outlook: PrecipitationOutlook?,
        hours: List<HourlyForecast>,
        hourStart: Instant,
    ): LaterPrecipitationRelation? {
        if (!current.condition.isPrecipitation || outlook == null || !outlook.start.isAfter(hourStart)) return null
        if (strength(outlook) > strength(current.condition)) return LaterPrecipitationRelation.INTENSIFIES
        var dryRun = 0
        var longestDryRun = 0
        for (hour in hours.filter { it.time.isAfter(hourStart) && it.time.isBefore(outlook.start) }) {
            dryRun = if (isWetCandidate(hour)) 0 else dryRun + 1
            longestDryRun = max(longestDryRun, dryRun)
        }
        return if (longestDryRun >= 2) LaterPrecipitationRelation.RESUMES else LaterPrecipitationRelation.CONTINUES
    }

    internal fun intensity(run: List<HourlyForecast>, maxRate: Double?): PrecipitationIntensity {
        if (maxRate != null) {
            if (maxRate >= thresholds.heavyRainMmPerHour) return PrecipitationIntensity.HEAVY
            if (maxRate >= thresholds.moderateRainMmPerHour) return PrecipitationIntensity.MODERATE
            return PrecipitationIntensity.LIGHT
        }
        // No amounts: only the provider's explicit condition may indicate intensity.
        if (run.any { it.condition == WeatherCondition.HEAVY_RAIN }) return PrecipitationIntensity.HEAVY
        if (run.all { it.condition == WeatherCondition.DRIZZLE }) return PrecipitationIntensity.LIGHT
        return PrecipitationIntensity.UNKNOWN
    }

    // Wind

    internal fun windOutlook(current: CurrentWeather, hours: List<HourlyForecast>): WindOutlook {
        val maxSpeed = (hours.mapNotNull { it.windSpeedKmh } + current.windSpeedKmh).max()
        val maxGust = (hours.mapNotNull { it.windGustKmh } + listOfNotNull(current.windGustKmh)).maxOrNull()
        val strong = maxSpeed >= thresholds.strongWindKmh || (maxGust ?: 0.0) >= thresholds.strongGustKmh
        return WindOutlook(maxSpeed, maxGust, strong)
    }

    companion object {
        /**
         * How soon forecast precipitation must start to change the character's look (seconds).
         * The look shows the weather *now*; rain expected later is told in words, not shown.
         */
        const val THEME_PRECIPITATION_LEAD_TIME: Double = 60.0 * 60

        /** drizzle 1 · rain/snow/sleet 2 · heavy 3 · thunderstorm 4 */
        internal fun strength(condition: WeatherCondition): Int = when (condition) {
            WeatherCondition.DRIZZLE -> 1
            WeatherCondition.HEAVY_RAIN -> 3
            WeatherCondition.THUNDERSTORM -> 4
            else -> if (condition.isPrecipitation) 2 else 0
        }

        /** Unknown intensity counts as ordinary (2) so it never claims "heavier". */
        internal fun strength(outlook: PrecipitationOutlook): Int {
            if (outlook.kind == PrecipitationKind.THUNDERSTORM) return 4
            return when (outlook.intensity) {
                PrecipitationIntensity.HEAVY -> 3
                PrecipitationIntensity.LIGHT -> 1
                PrecipitationIntensity.MODERATE, PrecipitationIntensity.UNKNOWN -> 2
            }
        }

        fun theme(
            current: CurrentWeather,
            precipitation: PrecipitationOutlook?,
            wind: WindOutlook?,
            now: Instant,
            leadTimeSeconds: Double = THEME_PRECIPITATION_LEAD_TIME,
        ): WeatherTheme {
            val imminent = precipitation?.takeIf { it.isLikely && it.start.secondsSince(now) <= leadTimeSeconds }
            if (current.condition == WeatherCondition.THUNDERSTORM || imminent?.kind == PrecipitationKind.THUNDERSTORM) {
                return WeatherTheme.STORMY
            }
            if (current.condition == WeatherCondition.SNOW || current.condition == WeatherCondition.SLEET ||
                imminent?.kind == PrecipitationKind.SNOW
            ) {
                return WeatherTheme.SNOWY
            }
            if (current.condition.isPrecipitation || imminent != null) return WeatherTheme.RAINY
            if (wind?.isStrong == true || current.condition == WeatherCondition.WINDY) return WeatherTheme.WINDY
            if (!current.isDay) return WeatherTheme.NIGHT
            return when (current.condition) {
                WeatherCondition.CLEAR, WeatherCondition.PARTLY_CLOUDY -> WeatherTheme.SUNNY
                else -> WeatherTheme.CLOUDY
            }
        }
    }
}
