package com.pofuduk.core.notification

import com.pofuduk.core.model.OfficialWarning
import com.pofuduk.core.model.WeatherDataSource
import com.pofuduk.core.model.WeatherSnapshot
import java.time.Instant

/**
 * What to schedule after each fresh forecast (port of the planning part of the iOS NotificationCoordinator).
 * Pure: the caller replaces all pending notifications with the result. Uses clock times in texts (Android delivery
 * may shift by minutes).
 */
class NotificationPlanner(
    private val rain: RainNotificationEngine = RainNotificationEngine(relativeTimes = false),
    private val morning: MorningBriefingEngine = MorningBriefingEngine(),
) {
    fun plan(
        snapshot: WeatherSnapshot?,
        warnings: List<OfficialWarning>,
        preferences: NotificationPreferences,
        morningSettings: MorningBriefingSettings,
        permissionGranted: Boolean,
        log: NotificationDeliveryLog,
        now: Instant,
    ): List<PlannedNotification> {
        // Never from demo data, never without the user's opt-in and the system permission.
        if (snapshot == null || snapshot.source == WeatherDataSource.DEMO || !preferences.isEnabled || !permissionGranted) {
            return emptyList()
        }
        log.prune(now.minusSeconds(2 * 86_400))
        val planned = rain.plan(snapshot, warnings, preferences, log, now).toMutableList()
        // Morning briefing: user-chosen time, so quiet hours don't apply; still deduplicated.
        morning.notification(snapshot, morningSettings, now)?.let { m ->
            val fired = log.fireDate(m.id)
            if (fired == null || fired.isAfter(now)) planned += m
        }
        planned.forEach { log.record(it.id, it.fireDate) }
        return planned
    }
}
