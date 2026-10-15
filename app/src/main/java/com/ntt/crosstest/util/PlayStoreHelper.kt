package com.ntt.crosstest.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class PlayStoreMetadata(
    val appName: String = "",
    val iconUrl: String = "",
)

object PlayStoreHelper {

    private val ID_PARAM_REGEX = Regex("[?&]id=([a-zA-Z0-9_.]+)")
    private val TESTING_URL_REGEX = Regex("/apps/testing/([a-zA-Z0-9_.]+)")
    private val INTERNAL_TESTING_URL_REGEX = Regex("/apps/internaltest/([a-zA-Z0-9_.]+)")
    private val PACKAGE_NAME_STRICT_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val PACKAGE_NAME_ANYWHERE_REGEX = Regex("([a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+)")

    private val DOMAIN_BLACKLIST = setOf(
        "play.google.com", "google.com", "android.com", "android.googlesource.com",
        "gstatic.com", "googleapis.com", "goo.gl", "http", "https"
    )

    private fun isDomainOrHost(str: String): Boolean {
        val lower = str.lowercase().trim()
        return DOMAIN_BLACKLIST.contains(lower) || lower.endsWith(".google.com")
    }

    data class InstalledAppItem(
        val packageName: String,
        val appName: String,
        val iconDrawable: Drawable? = null,
    )

    fun isValidPackageName(pkg: String): Boolean {
        val trimmed = pkg.trim()
        return PACKAGE_NAME_STRICT_REGEX.matches(trimmed) && !isDomainOrHost(trimmed)
    }

    /**
     * Extracts package name from various link formats:
     * - https://play.google.com/store/apps/details?id=com.example.app
     * - https://play.google.com/apps/testing/com.example.app
     * - https://play.google.com/apps/internaltest/com.example.app
     * - market://details?id=com.example.app
     * - com.example.app
     */
    fun extractPackageName(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        // 1. Try URL parameter `id=`
        ID_PARAM_REGEX.find(trimmed)?.groupValues?.get(1)?.let {
            val pkg = it.substringBefore("&").substringBefore("#").substringBefore("/").trim()
            if (pkg.isNotBlank() && !isDomainOrHost(pkg)) return pkg
        }

        // 2. Try testing track path `/apps/testing/com.example.app`
        TESTING_URL_REGEX.find(trimmed)?.groupValues?.get(1)?.let {
            val pkg = it.substringBefore("?").substringBefore("&").substringBefore("#").substringBefore("/").trim()
            if (pkg.isNotBlank() && !isDomainOrHost(pkg)) return pkg
        }

        // 3. Try internal testing track path `/apps/internaltest/...`
        INTERNAL_TESTING_URL_REGEX.find(trimmed)?.groupValues?.get(1)?.let {
            val pkg = it.substringBefore("?").substringBefore("&").substringBefore("#").substringBefore("/").trim()
            if (pkg.isNotBlank() && !isDomainOrHost(pkg)) return pkg
        }

        // 4. Try plain package name format
        if (PACKAGE_NAME_STRICT_REGEX.matches(trimmed) && !isDomainOrHost(trimmed)) {
            return trimmed
        }

        // 5. Fallback search anywhere in string (skip known domains)
        val matches = PACKAGE_NAME_ANYWHERE_REGEX.findAll(trimmed)
        for (m in matches) {
            val candidate = m.groupValues[1]
            if (!isDomainOrHost(candidate) && candidate.contains(".")) {
                return candidate
            }
        }
        return null
    }

    /**
     * Attempts to fetch app title and icon URL from public Google Play Store page.
     * Uses Dispatchers.IO.
     */
    suspend fun fetchPlayStoreMetadata(packageName: String): PlayStoreMetadata? = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext null
        try {
            val url = URL("https://play.google.com/store/apps/details?id=$packageName&hl=vi")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            }

            if (conn.responseCode !in 200..299) {
                return@withContext null
            }

            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            // Extract title via og:title or <title>
            var title = extractMetaContent(html, "og:title")
            if (title.isNullOrBlank()) {
                val titleTagMatch = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)
                title = titleTagMatch?.groupValues?.get(1)
            }

            val cleanedTitle = title.orEmpty()
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(" - Ứng dụng trên Google Play", "")
                .replace(" - Apps on Google Play", "")
                .replace(" - Android Apps on Google Play", "")
                .split(" - ")
                .firstOrNull()
                ?.trim()
                .orEmpty()

            // Extract icon via og:image or twitter:image
            var iconUrl = extractMetaContent(html, "og:image")
            if (iconUrl.isNullOrBlank()) {
                iconUrl = extractMetaContent(html, "twitter:image")
            }
            if (iconUrl.isNullOrBlank()) {
                val imgMatch = Regex("""<img[^>]+itemprop=["']image["'][^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                iconUrl = imgMatch?.groupValues?.get(1)
            }

            if (cleanedTitle.isNotBlank() || !iconUrl.isNullOrBlank()) {
                PlayStoreMetadata(
                    appName = cleanedTitle,
                    iconUrl = iconUrl.orEmpty(),
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractMetaContent(html: String, key: String): String? {
        val tagRegex = Regex("""<meta\s+[^>]*?(?:property|name)=["']$key["'][^>]*?>""", RegexOption.IGNORE_CASE)
        val tag = tagRegex.find(html)?.value ?: return null
        val contentRegex = Regex("""content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return contentRegex.find(tag)?.groupValues?.get(1)
    }

    /**
     * Returns list of user-installed non-system apps (or updated apps) with launcher intents.
     */
    fun getInstalledUserApps(context: Context): List<InstalledAppItem> {
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val result = mutableListOf<InstalledAppItem>()
            val myPackage = context.packageName

            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                if (pkg.packageName == myPackage) continue

                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                val hasLauncher = pm.getLaunchIntentForPackage(pkg.packageName) != null
                if ((!isSystem || isUpdatedSystem) && hasLauncher) {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    result.add(
                        InstalledAppItem(
                            packageName = pkg.packageName,
                            appName = label,
                            iconDrawable = icon
                        )
                    )
                }
            }
            result.sortedBy { it.appName.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Safely retrieves the application icon as a Drawable if installed on this device (cached via InstalledAppCache).
     */
    fun getInstalledAppDrawable(context: Context, packageName: String): Drawable? {
        return InstalledAppCache.getInstalledAppDrawable(context, packageName)
    }

    /**
     * Converts any Drawable (including AdaptiveIconDrawable) to a clean Bitmap.
     */
    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 192
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 192
        val bitmap = Bitmap.createBitmap(width.coerceIn(96, 256), height.coerceIn(96, 256), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Checks if the app is already installed on this Android device.
     * If installed, extracts its label and base64 PNG icon.
     */
    fun getInstalledAppMetadata(context: Context, packageName: String): PlayStoreMetadata? {
        if (packageName.isBlank()) return null
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val drawable = pm.getApplicationIcon(appInfo)
            val base64Icon = drawableToBase64(drawable).orEmpty()
            PlayStoreMetadata(
                appName = label,
                iconUrl = base64Icon,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Converts a Drawable into a compact Base64 PNG data URI (e.g. data:image/png;base64,...).
     */
    fun drawableToBase64(drawable: Drawable): String? {
        return try {
            val bitmap = drawableToBitmap(drawable)
            val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 90, stream)
            val bytes = stream.toByteArray()
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Converts a user-selected image Uri into a compact Base64 PNG data URI.
     */
    fun uriToBase64(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream) ?: return null
                val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
                val bytes = out.toByteArray()
                "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (_: Exception) {
            null
        }
    }
}
