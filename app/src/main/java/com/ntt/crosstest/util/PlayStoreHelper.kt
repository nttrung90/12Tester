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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class PlayStoreReleaseState {
    PRODUCTION,
    PRE_REGISTRATION,
    OPEN_TESTING,
    NOT_PUBLIC,
    UNKNOWN,
}

data class PlayStoreMetadata(
    val appName: String = "",
    val iconUrl: String = "",
    val releaseState: PlayStoreReleaseState = PlayStoreReleaseState.UNKNOWN,
    val releasedDate: String? = null,
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

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val LENIENT_JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val JSON_LD_SCRIPT_REGEX =
        Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
    private val DS5_KEY_REGEX =
        Regex("""key\s*:\s*['"]ds:5['"]""", RegexOption.IGNORE_CASE)
    private val DATA_FIELD_REGEX =
        Regex("""\bdata\s*:""", RegexOption.IGNORE_CASE)
    private val SCRIPT_TAG_REGEX = Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
    private val STYLE_TAG_REGEX = Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE)
    private val NOSCRIPT_TAG_REGEX = Regex("""<noscript[\s\S]*?</noscript>""", RegexOption.IGNORE_CASE)
    private val META_TAG_REGEX = Regex("""<meta[^>]*>""", RegexOption.IGNORE_CASE)
    private val TITLE_TAG_REGEX = Regex("""<title[\s\S]*?</title>""", RegexOption.IGNORE_CASE)
    private val H1_TAG_REGEX = Regex("""<h1[^>]*>[\s\S]*?</h1>""", RegexOption.IGNORE_CASE)
    private val DATA_GID_DESC_REGEX = Regex("""<div[^>]*data-g-id=["']description["'][^>]*>[\s\S]*?</div>""", RegexOption.IGNORE_CASE)
    private val ITEMPROP_DESC_REGEX = Regex("""<div[^>]*itemprop=["']description["'][^>]*>[\s\S]*?</div>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    private val OPEN_TESTING_SYSTEM_MARKERS = listOf(
        "this app is in development",
        "private feedback to developer",
        "you're an internal tester",
        "this app is currently in open testing",
        "app is currently in early access",
        "this app may be unstable",
        "you're a beta tester",
        "you are a beta tester",
    )

    internal data class Ds5Result(
        val ds5Found: Boolean,
        val availCode: Int?,
        val releasedDate: String?,
        val devTitle: String? = null,
        val devDescription: String? = null,
    )

    internal data class PlayStoreHtmlAnalysis(
        val metadata: PlayStoreMetadata,
        val hasSoftwareApplicationJsonLd: Boolean,
        val hasInStockOffer: Boolean,
        val availCode: Int?,
        val ds5Found: Boolean,
    )

    /**
     * Pure parser for public Google Play Store HTML.
     * Uses 3 layers:
     * 1. JSON-LD `SoftwareApplication` + `offers` (`InStock`)
     * 2. `ds:5` (`availCode` at `[1][2][18][0]`, `released` at `[1][2][10][0]`)
     * 3. System Open Testing / Early Access check after removing developer title/description
     */
    fun parsePlayStoreHtml(html: String): PlayStoreMetadata =
        parsePlayStoreHtmlInternal(html).metadata

    internal fun parsePlayStoreHtmlInternal(html: String): PlayStoreHtmlAnalysis {
        var title = extractMetaContent(html, "og:title")
        if (title.isNullOrBlank()) {
            val titleTagMatch = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)
            title = titleTagMatch?.groupValues?.get(1)
        }

        var cleanedTitle = decodeHtmlEntities(title.orEmpty())
            .replace(" - Ứng dụng trên Google Play", "")
            .replace(" - Apps on Google Play", "")
            .replace(" - Android Apps on Google Play", "")
            .split(" - ")
            .firstOrNull()
            ?.trim()
            .orEmpty()

        var iconUrl = extractMetaContent(html, "og:image")
        if (iconUrl.isNullOrBlank()) {
            iconUrl = extractMetaContent(html, "twitter:image")
        }
        if (iconUrl.isNullOrBlank()) {
            val imgMatch = Regex("""<img[^>]+itemprop=["']image["'][^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            iconUrl = imgMatch?.groupValues?.get(1)
        }

        val softwareAppJsonLd = findSoftwareApplicationJsonLd(html)
        if (softwareAppJsonLd == null) {
            return PlayStoreHtmlAnalysis(
                metadata = PlayStoreMetadata(
                    appName = cleanedTitle,
                    iconUrl = iconUrl.orEmpty(),
                    releaseState = PlayStoreReleaseState.UNKNOWN,
                    releasedDate = null,
                ),
                hasSoftwareApplicationJsonLd = false,
                hasInStockOffer = false,
                availCode = null,
                ds5Found = false,
            )
        }

        val devName = decodeHtmlEntities((softwareAppJsonLd["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()).trim()
        val devDescription = decodeHtmlEntities((softwareAppJsonLd["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()).trim()
        if (cleanedTitle.isBlank() && devName.isNotBlank()) {
            cleanedTitle = devName
        }
        if (iconUrl.isNullOrBlank()) {
            iconUrl = (softwareAppJsonLd["image"] as? JsonPrimitive)?.contentOrNull
        }

        val hasInStockOffer = hasInStockOffer(softwareAppJsonLd["offers"])
        val ds5 = extractDs5Fields(html)
        val ds5Found = ds5.ds5Found
        val availCode = ds5.availCode
        val releasedDate = ds5.releasedDate

        val releaseState = when {
            availCode == 1 -> PlayStoreReleaseState.PRE_REGISTRATION
            !hasInStockOffer -> PlayStoreReleaseState.PRE_REGISTRATION
            hasOpenTestingSystemMarker(
                html = html,
                devName = devName,
                devDescription = devDescription,
                cleanedTitle = cleanedTitle,
                ds5Title = ds5.devTitle,
                ds5Description = ds5.devDescription,
            ) -> PlayStoreReleaseState.OPEN_TESTING
            ds5Found && availCode == 2 && releasedDate == null -> PlayStoreReleaseState.OPEN_TESTING
            availCode == 2 || releasedDate != null -> PlayStoreReleaseState.PRODUCTION
            else -> PlayStoreReleaseState.UNKNOWN
        }

        return PlayStoreHtmlAnalysis(
            metadata = PlayStoreMetadata(
                appName = cleanedTitle,
                iconUrl = iconUrl.orEmpty(),
                releaseState = releaseState,
                releasedDate = if (releaseState == PlayStoreReleaseState.PRODUCTION) releasedDate else null,
            ),
            hasSoftwareApplicationJsonLd = true,
            hasInStockOffer = hasInStockOffer,
            availCode = availCode,
            ds5Found = ds5Found,
        )
    }

    private fun decodeHtmlEntities(input: String): String = input
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun findSoftwareApplicationJsonLd(html: String): JsonObject? {
        for (match in JSON_LD_SCRIPT_REGEX.findAll(html)) {
            val raw = match.groupValues[1].trim()
            if (raw.isBlank()) continue
            val root = try {
                LENIENT_JSON.parseToJsonElement(raw)
            } catch (_: Exception) {
                continue
            }
            when (root) {
                is JsonObject -> {
                    if ((root["@type"] as? JsonPrimitive)?.contentOrNull.equals("SoftwareApplication", ignoreCase = true)) {
                        return root
                    }
                }
                is JsonArray -> {
                    for (el in root) {
                        val obj = el as? JsonObject ?: continue
                        if ((obj["@type"] as? JsonPrimitive)?.contentOrNull.equals("SoftwareApplication", ignoreCase = true)) {
                            return obj
                        }
                    }
                }
                else -> {}
            }
        }
        return null
    }

    private fun hasInStockOffer(offersElement: kotlinx.serialization.json.JsonElement?): Boolean {
        if (offersElement == null) return false
        val offerObjects = when (offersElement) {
            is JsonObject -> listOf(offersElement)
            is JsonArray -> offersElement.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
        return offerObjects.any { offer ->
            val availability = (offer["availability"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            availability.endsWith("/InStock", ignoreCase = true) || availability.equals("InStock", ignoreCase = true)
        }
    }

    private fun extractDs5Fields(html: String): Ds5Result {
        return try {
            val keyMatch = DS5_KEY_REGEX.find(html) ?: return Ds5Result(false, null, null)
            val dataMatch = DATA_FIELD_REGEX.find(html, keyMatch.range.last + 1)
                ?.takeIf { it.range.first - keyMatch.range.last <= 300 }
                ?: return Ds5Result(false, null, null)
            val startIdx = html.indexOf('[', dataMatch.range.last + 1)
            if (startIdx < 0) return Ds5Result(false, null, null)
            val arrayLiteral = extractJsonArrayLiteral(html, startIdx) ?: return Ds5Result(false, null, null)
            val ds5 = LENIENT_JSON.parseToJsonElement(arrayLiteral) as? JsonArray ?: return Ds5Result(false, null, null)

            val section = ((ds5.getOrNull(1) as? JsonArray)?.getOrNull(2)) as? JsonArray
            val availCode = ((section?.getOrNull(18) as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.intOrNull
            val released = ((section?.getOrNull(10) as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val devTitle = ((section?.getOrNull(0) as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val devDescription = ((section?.getOrNull(72) as? JsonArray)?.getOrNull(0) as? JsonArray)
                ?.getOrNull(1)
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            Ds5Result(
                ds5Found = true,
                availCode = availCode,
                releasedDate = released,
                devTitle = devTitle,
                devDescription = devDescription,
            )
        } catch (_: Exception) {
            Ds5Result(false, null, null)
        }
    }

    private fun extractJsonArrayLiteral(source: String, openBracketIndex: Int): String? {
        if (openBracketIndex !in source.indices || source[openBracketIndex] != '[') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openBracketIndex until source.length) {
            val ch = source[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(openBracketIndex, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun hasOpenTestingSystemMarker(
        html: String,
        devName: String,
        devDescription: String,
        cleanedTitle: String,
        ds5Title: String? = null,
        ds5Description: String? = null,
    ): Boolean {
        var stripped = html
            .replace(SCRIPT_TAG_REGEX, " ")
            .replace(STYLE_TAG_REGEX, " ")
            .replace(NOSCRIPT_TAG_REGEX, " ")
            .replace(META_TAG_REGEX, " ")
            .replace(TITLE_TAG_REGEX, " ")
            .replace(H1_TAG_REGEX, " ")
            .replace(DATA_GID_DESC_REGEX, " ")
            .replace(ITEMPROP_DESC_REGEX, " ")
            .replace(HTML_TAG_REGEX, " ")
        stripped = decodeHtmlEntities(stripped).replace(WHITESPACE_REGEX, " ")

        val exclusions = listOfNotNull(devDescription, devName, cleanedTitle, ds5Description, ds5Title)
            .map { it.replace(WHITESPACE_REGEX, " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }

        for (text in exclusions) {
            stripped = stripped.replace(text, " ", ignoreCase = true)
        }
        val normalized = stripped.lowercase()
        return OPEN_TESTING_SYSTEM_MARKERS.any { marker -> normalized.contains(marker) }
    }

    /**
     * Attempts to fetch app title, icon URL, and public Play Store release state anonymously.
     * Uses 2-step HEAD -> GET (`gl=US`, with `gl=VN` fallback when geo-restricted).
     */
    suspend fun fetchPlayStoreMetadata(packageName: String): PlayStoreMetadata? = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext null
        try {
            val usUrl = "https://play.google.com/store/apps/details?id=$packageName&hl=en&gl=US"
            val headResult = probeHeadStatus(usUrl)
            if (headResult == 404) {
                return@withContext PlayStoreMetadata(releaseState = PlayStoreReleaseState.NOT_PUBLIC)
            }
            if (headResult != null && (headResult == 429 || headResult >= 500 || headResult == -1)) {
                return@withContext PlayStoreMetadata(releaseState = PlayStoreReleaseState.UNKNOWN)
            }

            val usHtml = fetchHtmlAnonymous(usUrl) ?: return@withContext null
            val usAnalysis = parsePlayStoreHtmlInternal(usHtml)

            val needsVnFallback = usAnalysis.hasSoftwareApplicationJsonLd &&
                usAnalysis.metadata.releaseState != PlayStoreReleaseState.OPEN_TESTING &&
                usAnalysis.availCode != 1 &&
                (usAnalysis.availCode != 2 || !usAnalysis.hasInStockOffer)

            if (needsVnFallback) {
                val vnUrl = "https://play.google.com/store/apps/details?id=$packageName&hl=en&gl=VN"
                val vnHtml = fetchHtmlAnonymous(vnUrl)
                if (vnHtml != null) {
                    val vnAnalysis = parsePlayStoreHtmlInternal(vnHtml)
                    if (vnAnalysis.metadata.releaseState != PlayStoreReleaseState.UNKNOWN) {
                        val resolved = vnAnalysis.metadata
                        return@withContext resolved.copy(
                            appName = resolved.appName.ifBlank { usAnalysis.metadata.appName },
                            iconUrl = resolved.iconUrl.ifBlank { usAnalysis.metadata.iconUrl },
                        )
                    }
                }
            }

            val meta = usAnalysis.metadata
            if (meta.appName.isNotBlank() || meta.iconUrl.isNotBlank() || meta.releaseState != PlayStoreReleaseState.UNKNOWN) {
                meta
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun probeHeadStatus(targetUrl: String): Int? {
        return try {
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            try {
                val code = conn.responseCode
                val finalUrl = conn.url?.toString().orEmpty()
                if (finalUrl.contains("/sorry/", ignoreCase = true)) -1 else code
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchHtmlAnonymous(targetUrl: String): String? {
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        return try {
            val code = conn.responseCode
            val finalUrl = conn.url?.toString().orEmpty()
            if (code !in 200..299 || finalUrl.contains("/sorry/", ignoreCase = true)) {
                null
            } else {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } finally {
            conn.disconnect()
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
            val scaled = if (bitmap.width == 128 && bitmap.height == 128) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, 128, 128, true)
            }
            try {
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 90, stream)
                val bytes = stream.toByteArray()
                "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                // Không recycle bitmap gốc nếu nó thuộc BitmapDrawable.
                if (scaled !== bitmap) scaled.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Converts a user-selected image Uri into a compact Base64 PNG data URI.
     * Sample trước khi decode để ảnh camera lớn không làm OOM khi chỉ cần icon 128px.
     */
    fun uriToBase64(context: Context, uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 || bounds.outHeight / (sample * 2) >= 256) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null
            val scaled = if (bitmap.width == 128 && bitmap.height == 128) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, 128, 128, true)
            }
            try {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
                val bytes = out.toByteArray()
                "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }
}
