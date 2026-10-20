package com.ntt.crosstest.util

import android.content.Context
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface PlayUpdateResult {
    data class Available(val versionCode: Int) : PlayUpdateResult
    data object NotAvailable : PlayUpdateResult
    data object Unknown : PlayUpdateResult
}

/**
 * Asks Play Store for the update state of the installed build. Works only when
 * the app was installed from Play (including closed testing); sideloaded builds
 * fall back to [Unknown] so callers can use the Supabase config instead.
 */
object PlayStoreUpdateChecker {

    suspend fun checkForUpdate(context: Context): PlayUpdateResult = withContext(Dispatchers.IO) {
        try {
            val info = AppUpdateManagerFactory.create(context).appUpdateInfo.await()
            when (info.updateAvailability()) {
                UpdateAvailability.UPDATE_AVAILABLE,
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    val versionCode = info.availableVersionCode()
                    if (versionCode > 0) {
                        PlayUpdateResult.Available(versionCode)
                    } else {
                        PlayUpdateResult.Unknown
                    }
                }
                UpdateAvailability.UPDATE_NOT_AVAILABLE -> PlayUpdateResult.NotAvailable
                else -> PlayUpdateResult.Unknown
            }
        } catch (_: InstallException) {
            PlayUpdateResult.Unknown
        } catch (_: Exception) {
            PlayUpdateResult.Unknown
        }
    }
}
