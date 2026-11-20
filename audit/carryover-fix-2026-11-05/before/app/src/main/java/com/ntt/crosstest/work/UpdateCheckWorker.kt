package com.ntt.crosstest.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.data.AppConfigRepository
import com.ntt.crosstest.data.SettingsRepository
import com.ntt.crosstest.data.SupabaseHolder
import com.ntt.crosstest.data.UpdateStatus
import kotlinx.coroutines.flow.first

/**
 * Runs periodically (every 12h, scheduled from TestCheoApp). Fetches the
 * remote app config and posts a notification when a newer version is
 * available. Skips the notification if the same latestVersionCode was
 * already announced (stored in DataStore).
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!SupabaseHolder.isInitialized()) return Result.success()
        return try {
            val repo = AppConfigRepository(SupabaseHolder.get())
            val config = repo.getAppConfig(forceRefresh = true) ?: return Result.success()

            val status = config.checkUpdateStatus(BuildConfig.VERSION_CODE)
            if (status == UpdateStatus.NO_UPDATE) return Result.success()

            val settings = SettingsRepository(applicationContext)
            val lastNotified = settings.updateNotifiedVersionCode.first()
            if (lastNotified == config.latestVersionCode) return Result.success()

            NotificationHelper.showUpdateAvailable(applicationContext, config.latestVersionName)
            settings.setUpdateNotifiedVersionCode(config.latestVersionCode)
            Result.success()
        } catch (_: Exception) {
            // Transient (network, …) — let WorkManager retry with backoff.
            Result.retry()
        }
    }
}
