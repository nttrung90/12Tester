package com.ntt.crosstest

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.ntt.crosstest.data.AppConfigRepository
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.PendingReportStore
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.SettingsRepository
import com.ntt.crosstest.data.SupabaseHolder
import com.ntt.crosstest.util.InstalledAppCache
import com.ntt.crosstest.util.ImageHashHelper
import com.ntt.crosstest.util.PreparedScreenshotCache
import com.ntt.crosstest.work.ReminderWorker
import com.ntt.crosstest.work.UpdateCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class TestCheoApp : Application(), ImageLoaderFactory {

    lateinit var authRepository: AuthRepository
        private set
    lateinit var campaignRepository: CampaignRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var appConfigRepository: AppConfigRepository
        private set
    lateinit var reportSubmitManager: ReportSubmitManager
        private set

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .allowHardware(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        InstalledAppCache.init(this)

        // Secrets come from local.properties (see README). Without them the app
        // shows a setup screen instead of crashing (checked in MainActivity).
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_ANON_KEY
        if (url.isNotBlank() && key.isNotBlank()) {
            val client = SupabaseHolder.init(url, key)
            authRepository = AuthRepository(client)
            campaignRepository = CampaignRepository(client, Localizer(this))
            appConfigRepository = AppConfigRepository(client, this)
            reportSubmitManager = ReportSubmitManager(this)
        }

        settingsRepository = SettingsRepository(this)
        // Dọn ảnh pending/prepared quá hạn sau khi process khởi động lại.
        PreparedScreenshotCache.cleanup(this)
        PendingReportStore.cleanup(this)
        scheduleDailyReminder()
        scheduleUpdateCheck()
    }

    /**
     * Schedules the daily 20:00 check-in reminder. The first run is aligned to
     * the next 20:00, then repeats every 24h. Note: WorkManager guarantees
     * *at-least-daily* delivery, not an exact clock time — on some devices the
     * notification may arrive a bit after 20:00 (Doze / battery optimization).
     */
    fun scheduleDailyReminder() {
        val now = LocalDateTime.now()
        var nextRun = now.toLocalDate().atTime(LocalTime.of(20, 0))
        if (!nextRun.isAfter(now)) {
            nextRun = nextRun.plusDays(1)
        }
        val initialDelayMinutes = Duration.between(now, nextRun).toMinutes().coerceAtLeast(1)

        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            .addTag(REMINDER_TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            REMINDER_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelDailyReminder() {
        WorkManager.getInstance(this).cancelUniqueWork(REMINDER_WORK_NAME)
    }

    /**
     * Checks the remote app config in the background (every 12h, first run after
     * ~10 minutes) and notifies when a newer version is available. The in-app
     * launch check (MainActivity) still handles blocking force updates.
     */
    fun scheduleUpdateCheck() {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
            .setInitialDelay(10, TimeUnit.MINUTES)
            .addTag(UPDATE_CHECK_TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UPDATE_CHECK_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Single sign-out path used by every screen: clears the remote session
     * (best effort), the in-memory repository cache and the on-disk session
     * snapshot so the next user/guest never sees the previous account's data.
     */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun performSignOut() {
        try {
            authRepository.signOut()
        } catch (_: Exception) {
            // Even if the remote sign-out fails (e.g. offline), clear local state.
        }
        campaignRepository.clearMemoryCache()
        SessionCacheManager.clear(this)
        ImageHashHelper.clearCache()
        if (::reportSubmitManager.isInitialized) {
            reportSubmitManager.cancelAllPendingWork()
        }
        withContext(Dispatchers.IO) {
            // Ảnh remote (screenshot/icon) và ảnh đang chờ nộp không được
            // tồn tại sau khi đổi tài khoản.
            val loader = coil.Coil.imageLoader(this@TestCheoApp)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
            PreparedScreenshotCache.clear(this@TestCheoApp)
            PendingReportStore.clear(this@TestCheoApp)
        }
    }

    companion object {
        const val REMINDER_WORK_NAME = "daily_checkin_reminder"
        const val REMINDER_TAG = "checkin_reminder"
        const val UPDATE_CHECK_WORK_NAME = "app_update_check"
        const val UPDATE_CHECK_TAG = "app_update_check"
    }
}
