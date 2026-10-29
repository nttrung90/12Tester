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

            // Read-only RPC: không gọi getMyDashboard ở background vì RPC đó
            // ghi nhận đăng nhập và sẽ làm sai nhiệm vụ ngày của user.
            val daily = repo.getMyDailyTaskStatus()
            if (daily != null) {
                if (!daily.met && !daily.isPenaltyExempt && daily.carryoverReady) {
                    NotificationHelper.showDailyTaskReminder(applicationContext, daily)
                }
                return Result.success()
            }

            // Server chưa deploy migration 036 — fallback luồng nhắc check-in cũ.
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
