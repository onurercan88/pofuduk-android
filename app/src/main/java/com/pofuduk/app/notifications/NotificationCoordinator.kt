package com.pofuduk.app.notifications

import android.content.Context
import com.pofuduk.core.model.OfficialWarning
import com.pofuduk.core.model.WeatherSnapshot
import com.pofuduk.core.notification.MorningBriefingEngine
import com.pofuduk.core.notification.MorningBriefingSettings
import com.pofuduk.core.notification.MorningBriefingStore
import com.pofuduk.core.notification.NotificationPlanner
import com.pofuduk.core.notification.NotificationPreferenceStore
import com.pofuduk.core.notification.NotificationPreferences
import com.pofuduk.core.notification.PlannedNotification
import com.pofuduk.core.notification.StoredNotificationLog
import com.pofuduk.core.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId

data class NotificationState(
    val preferences: NotificationPreferences,
    val morning: MorningBriefingSettings,
    val permissionGranted: Boolean,
    /** The user already tried to turn notifications on (so a missing permission means "denied", not "not asked"). */
    val askedBefore: Boolean = false,
    val lastPlanned: List<PlannedNotification> = emptyList(),
)

/** Glue between preferences, the core planner and the alarm scheduler (shared by UI and background refresh). */
class NotificationCoordinator(private val context: Context, private val kv: KeyValueStore) {
    private val store = NotificationPreferenceStore(kv)
    private val morningStore = MorningBriefingStore(kv)
    private val log = StoredNotificationLog(kv)
    private val planner = NotificationPlanner()
    private val morningEngine = MorningBriefingEngine()
    private val scheduler = NotificationScheduler(context)
    private var lastSnapshot: WeatherSnapshot? = null
    private var lastWarnings: List<OfficialWarning> = emptyList()

    private val _state = MutableStateFlow(
        NotificationState(store.load(), morningStore.load(), NotificationChannels.permissionGranted(context), kv.getString(ASKED) == "1"),
    )
    val state: StateFlow<NotificationState> = _state.asStateFlow()

    // Morning briefing ("Pofuduk Günaydın")

    fun shouldGreet(zone: ZoneId, now: Instant = Instant.now()): Boolean = morningEngine.shouldGreet(now, _state.value.morning, zone)

    fun markGreeted(zone: ZoneId, now: Instant = Instant.now()) =
        updateMorning { it.copy(lastGreetedDay = MorningBriefingEngine.dayKey(now, zone)) }

    fun updateMorning(change: (MorningBriefingSettings) -> MorningBriefingSettings) {
        _state.update { it.copy(morning = change(it.morning)) }
        morningStore.save(_state.value.morning)
        replan()
    }

    /** Turning on the morning text notification also turns notifications on (permission asked by the UI first). */
    fun setMorningNotification(on: Boolean) {
        if (on && !_state.value.preferences.isEnabled) setEnabled(true)
        updateMorning { it.copy(sendsNotification = on && _state.value.preferences.isEnabled) }
    }

    // Weather notifications

    /** Call after the permission prompt (Android 13+) has been answered, or when switching off. */
    fun setEnabled(enabled: Boolean) {
        val granted = NotificationChannels.permissionGranted(context)
        if (enabled) kv.putString(ASKED, "1")
        _state.update {
            it.copy(permissionGranted = granted, askedBefore = it.askedBefore || enabled, preferences = it.preferences.copy(isEnabled = enabled && granted))
        }
        store.save(_state.value.preferences)
        replan()
    }

    fun update(change: (NotificationPreferences) -> NotificationPreferences) {
        _state.update { it.copy(preferences = change(it.preferences)) }
        store.save(_state.value.preferences)
        replan()
    }

    fun refreshPermission() {
        _state.update { it.copy(permissionGranted = NotificationChannels.permissionGranted(context)) }
    }

    /** Call after every fresh real forecast (foreground load or background refresh). Demo data plans nothing. */
    fun refresh(snapshot: WeatherSnapshot, warnings: List<OfficialWarning> = emptyList()) {
        lastSnapshot = snapshot
        lastWarnings = warnings
        replan()
    }

    /** "Verilerimi sil": defaults, delivery log cleared, pending notifications cancelled. */
    fun reset() {
        store.save(NotificationPreferences())
        morningStore.save(MorningBriefingSettings())
        log.prune(Instant.MAX)
        kv.remove(ASKED)
        lastSnapshot = null
        _state.update { NotificationState(NotificationPreferences(), MorningBriefingSettings(), NotificationChannels.permissionGranted(context)) }
        replan()
    }

    private companion object {
        const val ASKED = "pofuduk.notificationsAsked"
    }

    @Synchronized
    private fun replan() {
        val granted = NotificationChannels.permissionGranted(context)
        val s = _state.value
        val now = Instant.now()
        val planned = planner.plan(lastSnapshot, lastWarnings, s.preferences, s.morning, granted, log, now)
        // Without a fresh forecast in this process keep what is scheduled, unless notifications were switched off.
        if (lastSnapshot != null || !s.preferences.isEnabled || !granted) scheduler.replacePending(planned, now)
        _state.update { it.copy(permissionGranted = granted, lastPlanned = if (lastSnapshot != null) planned else it.lastPlanned) }
    }
}
