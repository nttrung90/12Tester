package com.ntt.crosstest.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SupabaseHolder
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs ~daily at 20:00 (scheduled from TestCheoApp). If the signed-in user has
 * joined any open campaign they have NOT checked in today, posts a notification.
 */
class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!SupabaseHolder.isInitialized()) return Result.success()
        return try {
            val supabase = SupabaseHolder.get()

            // Wait for the persisted session to load (autoLoadFromStorage).
            val status = withTimeoutOrNull(15_000) {
                supabase.auth.sessionStatus.first { it !is SessionStatus.Initializing }
            } ?: return Result.success()
            if (status !is SessionStatus.Authenticated) return Result.success()

            val repo = CampaignRepository(supabase, Localizer(applicationContext))
            val joined = repo.myJoinedCampaigns().filter { it.isOpen && it.id != null }
            // 1 RPC get_campaigns_stats thay cho 2 query × N app (checkin + report hôm nay).
            val stats = if (joined.isEmpty()) {
                emptyMap()
            } else {
                repo.getCampaignsStatsMap(joined.mapNotNull { it.id })
            }
            val missing = joined.filter { stats[it.id]?.hasTestedToday != true }

            if (missing.isNotEmpty()) {
                NotificationHelper.showCheckinReminder(
                    applicationContext,
                    missing.map { it.appName },
                )
            }
            Result.success()
        } catch (e: Exception) {
            // Transient (network, …) — let WorkManager retry with backoff.
            Result.retry()
        }
    }
}
