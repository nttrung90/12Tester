package com.ntt.crosstest.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper đọc mã phiên bản (versionCode) và tên phiên bản (versionName) của app đã cài đặt
 * trên thiết bị tester qua [PackageManager].
 *
 * GIỚI HẠN XÁC MINH CLIENT:
 * Phương thức này thực hiện đọc trực tiếp trên client thông qua API hệ thống Android.
 * Server và Google Play KHÔNG cung cấp cơ chế độc lập chứng thực bản cài đặt của ứng dụng
 * bên thứ ba trên máy tester. Do đó, việc xác minh dựa vào client và có giới hạn kỹ thuật
 * theo tài liệu KE_HOACH_THUONG_CAP_NHAT_PHIEN_BAN_2026-10-29.md.
 */
sealed class InstalledVersionResult {
    data class Installed(val versionCode: Long, val versionName: String) : InstalledVersionResult()
    data object NotInstalled : InstalledVersionResult()
    data class Unverifiable(val reason: String) : InstalledVersionResult()
}

object InstalledAppVersionReader {

    suspend fun readInstalledVersion(context: Context, packageName: String): InstalledVersionResult =
        withContext(Dispatchers.IO) {
            if (packageName.isBlank()) {
                return@withContext InstalledVersionResult.Unverifiable("Empty package name")
            }
            try {
                val packageManager = context.packageManager
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }

                val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                val versionName = packageInfo.versionName ?: ""

                InstalledVersionResult.Installed(
                    versionCode = versionCode,
                    versionName = versionName,
                )
            } catch (_: PackageManager.NameNotFoundException) {
                InstalledVersionResult.NotInstalled
            } catch (e: SecurityException) {
                InstalledVersionResult.Unverifiable("SecurityException: ${e.message}")
            } catch (e: Exception) {
                InstalledVersionResult.Unverifiable("Error: ${e.message}")
            }
        }
}
