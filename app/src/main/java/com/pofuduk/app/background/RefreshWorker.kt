package com.pofuduk.app.background

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pofuduk.app.graph
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Best-effort hourly forecast refresh for notifications. Never asks for location in the background: it uses the
 * user's chosen city or the last place, coarsened to ~1 km. Does nothing unless notifications are enabled.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val notifications = graph.notifications
        if (!notifications.state.value.preferences.isEnabled && !notifications.state.value.morning.sendsNotification) {
            return Result.success()
        }
        val place = graph.location.preference.backgroundPlace ?: return Result.success()
        return try {
            notifications.refresh(graph.repository.snapshot(place, forceRefresh = false))
            Log.d("Pofuduk", "background refresh done")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "pofuduk.refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
