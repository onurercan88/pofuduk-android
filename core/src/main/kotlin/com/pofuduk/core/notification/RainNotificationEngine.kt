package com.pofuduk.core.notification

import com.pofuduk.core.datetime.TurkishFormatting
import com.pofuduk.core.datetime.TurkishGrammar
import com.pofuduk.core.engine.PrecipitationIntensity
import com.pofuduk.core.engine.PrecipitationKind
import com.pofuduk.core.engine.WeatherSummaryEngine
import com.pofuduk.core.engine.WeatherThresholds
import com.pofuduk.core.model.OfficialWarning
import com.pofuduk.core.model.WarningLevel
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.util.isSameDay
import com.pofuduk.core.util.localDate
import com.pofuduk.core.util.plusSeconds
import com.pofuduk.core.util.roundedInt
import com.pofuduk.core.util.secondsSince
import com.pofuduk.core.util.startOfHour
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Plans local notifications from the latest forecast ("Yağmur Başlamadan Haber Ver").
 * Best effort by design: the OS decides when background work runs, so plans are only made within
 * [horizonSeconds] of the forecast's retrieval time and never from stale data.
 */
class RainNotificationEngine(
    val thresholds: WeatherThresholds = WeatherThresholds.DEFAULT,
    /** How far after the forecast retrieval we trust it enough to schedule a notification. */
    val horizonSeconds: Double = 6.0 * 3600,
    /** Day-to-day max temperature change (°C) worth a heads-up. */
    val temperatureChangeThreshold: Double = 8.0,
    /** Local time for the "tomorrow is different" heads-up. */
    val temperatureNoticeHour: Int = 19,
    /**
     * "Yaklaşık 30 dakika içinde" for events within 90 min (iOS default). Android delivers inexact alarms that may
     * shift by minutes, so it uses clock times only ("Saat 16.00 civarında"), which stay correct when late.
     */
    val relativeTimes: Boolean = true,
) {
    fun plan(
        snapshot: WeatherSnapshot,
        warnings: List<OfficialWarning> = emptyList(),
        preferences: NotificationPreferences,
        log: NotificationDeliveryLog,
        now: Instant,
    ): List<PlannedNotification> {
        if (!preferences.isEnabled) return emptyList()
        val zone = snapshot.place.timeZone
        val placeKey = String.format(Locale.ROOT, "%.2f_%.2f", snapshot.place.coordinate.latitude, snapshot.place.coordinate.longitude)
        val planned = mutableListOf<PlannedNotification>()

        // Official warnings are independent of forecast freshness.
        for (w in warnings.filter { it.isActive(now) && it.level >= WarningLevel.YELLOW }) {
            planned += PlannedNotification(
                id = "mgmWarning-${w.id}", category = NotificationCategory.MGM_WARNING, fireDate = now.plusSeconds(5),
                title = "MGM ${w.level.turkishName} uyarı", body = "${w.hazard}. ${w.description} Kaynak: MGM (resmi uyarı).",
                isTimeSensitive = w.level >= WarningLevel.ORANGE,
            )
        }

        // Never notify from stale forecasts.
        if (!snapshot.isStale(now)) {
            val hourStart = now.startOfHour(zone)
            val windowEnd = snapshot.retrievedAt.plusSeconds(horizonSeconds)
            val hours = snapshot.hourly.filter { !it.time.isBefore(hourStart) && it.time.isBefore(windowEnd) }
            val source = "Kaynak: ${snapshot.source.attribution} tahmini, saat ${TurkishFormatting.clockTime(snapshot.retrievedAt, zone)} itibarıyla."
            val place = TurkishGrammar.locative(snapshot.place.name)
            val lead = preferences.rainLeadMinutes * 60.0

            fun key(c: NotificationCategory, event: Instant): String {
                val x = event.atZone(zone)
                return "${keyName(c)}-$placeKey-${x.year}${x.monthValue}${x.dayOfMonth}${x.hour}"
            }
            /** Fire `lead` before the event; if that moment passed but the event hasn't started, fire now. */
            fun fireDate(event: Instant): Instant? {
                if (!event.isAfter(now)) return null
                return maxOf(event.plusSeconds(-lead), now.plusSeconds(60))
            }
            fun whenText(event: Instant): String {
                val minutes = (event.secondsSince(now) / 60).roundedInt()
                if (relativeTimes && minutes <= 90) return "Yaklaşık ${max(5, (minutes + 4) / 5 * 5)} dakika içinde"
                return "Saat ${TurkishFormatting.clockTime(event, zone)} civarında"
            }

            // Precipitation, using the user's probability threshold.
            val t = thresholds.copy(likelyPrecipitationProbability = preferences.rainProbabilityThreshold)
            val p = WeatherSummaryEngine(t).precipitationOutlook(hours)
            val pFire = p?.takeIf { it.isLikely }?.let { fireDate(it.start) }
            if (p != null && pFire != null) {
                val prob = p.maxProbability?.let { " (olasılık %$it)" } ?: ""
                val w = whenText(p.start)
                planned += when {
                    p.kind == PrecipitationKind.THUNDERSTORM -> PlannedNotification(
                        key(NotificationCategory.SEVERE_WEATHER, p.start), NotificationCategory.SEVERE_WEATHER, pFire,
                        "Gök gürültülü sağanak",
                        "$w $place gök gürültülü sağanak bekleniyor$prob. Açık alanlarda dikkatli ol. $source", false,
                    )
                    p.intensity == PrecipitationIntensity.HEAVY -> PlannedNotification(
                        key(NotificationCategory.HEAVY_RAIN, p.start), NotificationCategory.HEAVY_RAIN, pFire,
                        "Kuvvetli yağış bekleniyor",
                        "$w $place kuvvetli yağış bekleniyor$prob. Dışarı çıkarken dikkatli ol. $source", false,
                    )
                    else -> {
                        val noun = if (p.kind == PrecipitationKind.SNOW) "kar" else "yağmur"
                        PlannedNotification(
                            key(NotificationCategory.RAIN_SOON, p.start), NotificationCategory.RAIN_SOON, pFire,
                            if (p.kind == PrecipitationKind.SNOW) "Kar geliyor ❄️" else "Şemsiyeni unutma! ☂️",
                            "$w $place $noun bekleniyor$prob. $source", false,
                        )
                    }
                }
            }

            // Strong wind.
            val windy = hours.firstOrNull {
                (it.windSpeedKmh ?: 0.0) >= thresholds.strongWindKmh || (it.windGustKmh ?: 0.0) >= thresholds.strongGustKmh
            }
            val windFire = windy?.let { fireDate(it.time) }
            if (windy != null && windFire != null) {
                val speed = max(windy.windSpeedKmh ?: 0.0, windy.windGustKmh ?: 0.0).roundedInt()
                planned += PlannedNotification(
                    key(NotificationCategory.STRONG_WIND, windy.time), NotificationCategory.STRONG_WIND, windFire,
                    "Kuvvetli rüzgâr",
                    "${whenText(windy.time)} $place rüzgâr saatte $speed kilometreye kadar çıkabilir. Dışarı çıkarken dikkatli ol. $source",
                    false,
                )
            }

            // Tomorrow notably warmer/colder (daily data; announced this evening).
            val todayDate = now.localDate(zone)
            val today = todayDate.atStartOfDay(zone).toInstant()
            val tomorrow = todayDate.plusDays(1).atStartOfDay(zone).toInstant()
            val d0 = snapshot.daily.firstOrNull { it.date.isSameDay(today, zone) }
            val d1 = snapshot.daily.firstOrNull { it.date.isSameDay(tomorrow, zone) }
            val noticeFire = todayDate.atTime(temperatureNoticeHour, 0).atZone(zone).toInstant()
            if (d0 != null && d1 != null && abs(d1.maxTemperature - d0.maxTemperature) >= temperatureChangeThreshold &&
                noticeFire.isAfter(now)
            ) {
                val diff = abs(d1.maxTemperature - d0.maxTemperature).roundedInt()
                val direction = if (d1.maxTemperature < d0.maxTemperature) "daha serin" else "daha sıcak"
                planned += PlannedNotification(
                    key(NotificationCategory.TEMPERATURE_CHANGE, tomorrow), NotificationCategory.TEMPERATURE_CHANGE, noticeFire,
                    "Yarın hava değişiyor",
                    "Yarın en yüksek sıcaklık ${TurkishFormatting.temperature(d1.maxTemperature)}; bugünden yaklaşık $diff derece $direction. $source",
                    false,
                )
            }
        }

        val seen = mutableSetOf<String>()
        return planned
            .filter { preferences.allows(it.category) }
            // Quiet hours: only time-sensitive official warnings may break through.
            .filter { it.isTimeSensitive || !preferences.quietHours.contains(it.fireDate, zone) }
            // Dedup: never re-send something already delivered.
            .filter { n ->
                val fired = log.fireDate(n.id)
                if (fired != null && !fired.isAfter(now)) false else seen.add(n.id)
            }
            .sortedWith(compareBy<PlannedNotification> { it.category.priority }.thenBy { it.fireDate })
    }

    /** Same keys as iOS (camelCase raw values) so logs stay comparable across platforms. */
    private fun keyName(c: NotificationCategory): String = when (c) {
        NotificationCategory.RAIN_SOON -> "rainSoon"
        NotificationCategory.HEAVY_RAIN -> "heavyRain"
        NotificationCategory.STRONG_WIND -> "strongWind"
        NotificationCategory.SEVERE_WEATHER -> "severeWeather"
        NotificationCategory.MGM_WARNING -> "mgmWarning"
        NotificationCategory.MORNING_BRIEFING -> "morningBriefing"
        NotificationCategory.TEMPERATURE_CHANGE -> "temperatureChange"
        NotificationCategory.DESTINATION_UPDATE -> "destinationUpdate"
    }
}
