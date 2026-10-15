package com.ntt.crosstest.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ntt.crosstest.data.CampaignRepository
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

            val repo = CampaignRepository(supabase)
            val missing = repo.myJoinedCampaigns()
                .filter { it.isOpen && it.id != null }
                .filter { campaign ->
                    val campId = requireNotNull(campaign.id)
                    val hasCheckedIn = repo.hasCheckedInToday(campId)
                    val hasSubmittedReport = repo.getMyTodayReport(campId)?.let { it.isPending || it.isApproved } == true
                    !hasCheckedIn && !hasSubmittedReport
                }

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
