@file:UseSerializers(InstantSerializer::class)

package com.pofuduk.core.notification

import com.pofuduk.core.store.JsonStore
import com.pofuduk.core.store.KeyValueStore
import com.pofuduk.core.util.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class NotificationCategory {
    RAIN_SOON, HEAVY_RAIN, STRONG_WIND, SEVERE_WEATHER, MGM_WARNING, MORNING_BRIEFING, TEMPERATURE_CHANGE, DESTINATION_UPDATE;

    val turkishName: String
        get() = when (this) {
            RAIN_SOON -> "Yağmur başlamadan haber ver"
            HEAVY_RAIN -> "Kuvvetli yağış"
            STRONG_WIND -> "Kuvvetli rüzgâr"
            SEVERE_WEATHER -> "Gök gürültülü sağanak"
            MGM_WARNING -> "Resmi MGM uyarıları"
            MORNING_BRIEFING -> "Pofuduk Günaydın"
            TEMPERATURE_CHANGE -> "Belirgin sıcaklık değişimi"
            DESTINATION_UPDATE -> "Seyahat hava güncellemeleri"
        }

    /** Ordering priority (lower first). Verified official warnings always come first. */
    internal val priority: Int
        get() = when (this) {
            MGM_WARNING -> 0
            SEVERE_WEATHER -> 1
            HEAVY_RAIN -> 2
            STRONG_WIND -> 3
            RAIN_SOON -> 4
            TEMPERATURE_CHANGE -> 5
            MORNING_BRIEFING -> 6
            DESTINATION_UPDATE -> 7
        }
}

/** Local time-of-day window, may wrap midnight (e.g. 22:00–07:00). */
@Serializable
data class QuietHours(val enabled: Boolean = true, val startMinute: Int = 22 * 60, val endMinute: Int = 7 * 60) {
    fun contains(instant: Instant, zone: ZoneId): Boolean {
        if (!enabled || startMinute == endMinute) return false
        val z = instant.atZone(zone)
        val m = z.hour * 60 + z.minute
        return if (startMinute < endMinute) m in startMinute until endMinute else m >= startMinute || m < endMinute
    }
}

@Serializable
data class NotificationPreferences(
    /** Master switch. Off by default: the user opts in. */
    val isEnabled: Boolean = false,
    val enabledCategories: Set<NotificationCategory> = setOf(
        NotificationCategory.RAIN_SOON, NotificationCategory.HEAVY_RAIN, NotificationCategory.STRONG_WIND,
        NotificationCategory.SEVERE_WEATHER, NotificationCategory.MGM_WARNING,
    ),
    /** Minutes before expected rain. */
    val rainLeadMinutes: Int = 30,
    /** Minimum precipitation probability (%) for "rain soon". */
    val rainProbabilityThreshold: Int = 60,
    val quietHours: QuietHours = QuietHours(),
) {
    fun allows(category: NotificationCategory): Boolean = isEnabled && category in enabledCategories
}

data class PlannedNotification(
    /** Stable dedup key (category + place + event hour). */
    val id: String,
    val category: NotificationCategory,
    val fireDate: Instant,
    val title: String,
    val body: String,
    /** Official warnings may break through quiet hours (high priority channel). */
    val isTimeSensitive: Boolean,
)

/** Remembers which notifications were scheduled and when they fire, so a delivered one is never re-sent. */
interface NotificationDeliveryLog {
    fun fireDate(key: String): Instant?
    fun record(key: String, fireDate: Instant)
    fun prune(before: Instant)
}

class InMemoryNotificationLog : NotificationDeliveryLog {
    private val entries = ConcurrentHashMap<String, Instant>()
    override fun fireDate(key: String): Instant? = entries[key]
    override fun record(key: String, fireDate: Instant) { entries[key] = fireDate }
    override fun prune(before: Instant) { entries.entries.removeIf { it.value.isBefore(before) } }
}

/** Persistent log (epoch seconds per key) on a [KeyValueStore]. */
class StoredNotificationLog(store: KeyValueStore) : NotificationDeliveryLog {
    private val json = JsonStore(store, "pofuduk.notificationLog", MapSerializer(String.serializer(), Long.serializer()))
    private fun entries(): Map<String, Long> = json.load() ?: emptyMap()

    @Synchronized override fun fireDate(key: String): Instant? = entries()[key]?.let(Instant::ofEpochSecond)
    @Synchronized override fun record(key: String, fireDate: Instant) = json.save(entries() + (key to fireDate.epochSecond))
    @Synchronized override fun prune(before: Instant) = json.save(entries().filterValues { it >= before.epochSecond })
}

/** Persists notification preferences on device. */
class NotificationPreferenceStore(store: KeyValueStore) {
    private val json = JsonStore(store, "pofuduk.notificationPreferences", NotificationPreferences.serializer())
    fun load(): NotificationPreferences = json.load() ?: NotificationPreferences()
    fun save(p: NotificationPreferences) = json.save(p)
}
