package com.pofuduk.app.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pofuduk.app.MainActivity
import com.pofuduk.app.R
import com.pofuduk.app.data.SharedPreferencesStore
import com.pofuduk.core.notification.NotificationCategory
import com.pofuduk.core.notification.PlannedNotification
import java.time.Instant

/** One channel per category, so the user can tune each one in the system settings too. */
object NotificationChannels {
    fun id(category: NotificationCategory) = "pofuduk." + category.name.lowercase()

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = NotificationCategory.entries.map { c ->
            val importance = when (c) {
                NotificationCategory.MGM_WARNING, NotificationCategory.SEVERE_WEATHER -> NotificationManager.IMPORTANCE_HIGH
                NotificationCategory.MORNING_BRIEFING, NotificationCategory.TEMPERATURE_CHANGE,
                NotificationCategory.DESTINATION_UPDATE -> NotificationManager.IMPORTANCE_LOW
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            NotificationChannel(id(c), c.turkishName, importance)
        }
        manager.createNotificationChannels(channels)
    }

    fun permissionGranted(context: Context): Boolean {
        val runtime = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return runtime && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

/**
 * Schedules planned notifications without the exact-alarm permission: a 10-minute window alarm (`setWindow`) for the
 * normal case plus a Doze-capable fallback (`setAndAllowWhileIdle`, whose window the system may stretch). Whichever
 * fires first shows the notification; the other is skipped via the "shown" record. Pending ids are kept so a new plan
 * replaces the old one.
 */
class NotificationScheduler(private val context: Context) {
    private val store = SharedPreferencesStore(context, "pofuduk.alarms")
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /**
     * Replaces future alarms with [planned]. Alarms whose time has already come are *in flight* (the system delivers
     * inexact alarms within their window): they are kept, because the planner already counts them as delivered and
     * would not plan them again.
     */
    @Synchronized
    fun replacePending(planned: List<PlannedNotification>, now: Instant) {
        val nowMs = now.toEpochMilli()
        val (inFlight, future) = pending().partition { it.second <= nowMs }
        future.forEach { (id, _) ->
            alarms.cancel(intent(id, null, WINDOW))
            alarms.cancel(intent(id, null, IDLE))
        }
        planned.forEach { n ->
            val at = maxOf(n.fireDate.toEpochMilli(), nowMs + 1_000)
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, intent(n.id, n, WINDOW))
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(n.id, n, IDLE))
        }
        val keep = inFlight.filter { nowMs - it.second < MAX_LATENESS_MS && planned.none { p -> p.id == it.first } }
        val entries = keep + planned.map { it.id to it.fireDate.toEpochMilli() }
        store.putString(KEY, entries.joinToString("\n") { "${it.first}|${it.second}" })
    }

    private fun pending(): List<Pair<String, Long>> = store.getString(KEY)?.split('\n')?.mapNotNull { line ->
        val sep = line.lastIndexOf('|')
        if (sep < 0) null else line.substring(0, sep) to (line.substring(sep + 1).toLongOrNull() ?: return@mapNotNull null)
    }.orEmpty()

    private fun intent(id: String, n: PlannedNotification?, kind: String): PendingIntent {
        val i = Intent(context, NotificationReceiver::class.java).setAction("com.pofuduk.app.NOTIFY.$kind:$id")
        if (n != null) {
            i.putExtra(EXTRA_ID, n.id).putExtra(EXTRA_CATEGORY, n.category.name).putExtra(EXTRA_TITLE, n.title)
                .putExtra(EXTRA_BODY, n.body).putExtra(EXTRA_FIRE, n.fireDate.toEpochMilli())
        }
        return PendingIntent.getBroadcast(context, (kind + id).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val KEY = "pending"
        private const val WINDOW = "window"
        private const val IDLE = "idle"
        private const val WINDOW_MS = 10 * 60 * 1000L
        private const val SHOWN = "shown"

        /** True the first time [id] is shown; remembers ids for two days so the second alarm is skipped. */
        @Synchronized
        fun markShown(context: Context, id: String, now: Long = System.currentTimeMillis()): Boolean {
            val store = SharedPreferencesStore(context, "pofuduk.alarms")
            val entries = store.getString(SHOWN)?.split('\n')?.mapNotNull { line ->
                val sep = line.lastIndexOf('|')
                if (sep < 0) null else line.substring(0, sep) to (line.substring(sep + 1).toLongOrNull() ?: 0L)
            }?.filter { now - it.second < 2 * 86_400_000L }.orEmpty()
            if (entries.any { it.first == id }) return false
            store.putString(SHOWN, (entries + (id to now)).joinToString("\n") { "${it.first}|${it.second}" })
            return true
        }


        const val EXTRA_ID = "id"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_FIRE = "fire"

        /** A notification delivered much later than planned (deep Doze) would be misleading — drop it. */
        const val MAX_LATENESS_MS = 30 * 60 * 1000L
    }
}

/** Shows a planned notification when its alarm fires. */
class NotificationReceiver : BroadcastReceiver() {
    private fun skip(reason: String, category: NotificationCategory? = null) {
        Log.d(TAG, "skipped ${category ?: ""}: $reason")
    }

    private companion object {
        const val TAG = "Pofuduk"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Logs carry the category and reason only — never ids (they contain coarse coordinates) or text.
        val id = intent.getStringExtra(NotificationScheduler.EXTRA_ID) ?: return skip("no id")
        val category = runCatching { NotificationCategory.valueOf(intent.getStringExtra(NotificationScheduler.EXTRA_CATEGORY)!!) }
            .getOrNull() ?: return skip("no category")
        val fire = intent.getLongExtra(NotificationScheduler.EXTRA_FIRE, 0L)
        if (System.currentTimeMillis() - fire > NotificationScheduler.MAX_LATENESS_MS) return skip("too late", category)
        if (!NotificationChannels.permissionGranted(context)) return skip("no permission", category)
        if (!NotificationScheduler.markShown(context, id)) return skip("already shown", category)
        Log.d(TAG, "showing $category")

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val body = intent.getStringExtra(NotificationScheduler.EXTRA_BODY).orEmpty()
        val notification = NotificationCompat.Builder(context, NotificationChannels.id(category))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(intent.getStringExtra(NotificationScheduler.EXTRA_TITLE))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(if (category == NotificationCategory.MGM_WARNING) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }
}
