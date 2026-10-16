package com.ntt.crosstest

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.SettingsRepository
import com.ntt.crosstest.data.SupabaseHolder
import com.ntt.crosstest.util.InstalledAppCache
import com.ntt.crosstest.work.ReminderWorker
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
            campaignRepository = CampaignRepository(client)
        }

        settingsRepository = SettingsRepository(this)
        scheduleDailyReminder()
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
     * Single sign-out path used by every screen: clears the remote session
     * (best effort), the in-memory repository cache and the on-disk session
     * snapshot so the next user/guest never sees the previous account's data.
     */
    suspend fun performSignOut() {
        try {
            authRepository.signOut()
        } catch (_: Exception) {
            // Even if the remote sign-out fails (e.g. offline), clear local state.
        }
        campaignRepository.clearMemoryCache()
        SessionCacheManager.clear(this)
    }

    companion object {
        const val REMINDER_WORK_NAME = "daily_checkin_reminder"
        const val REMINDER_TAG = "checkin_reminder"
    }
}
