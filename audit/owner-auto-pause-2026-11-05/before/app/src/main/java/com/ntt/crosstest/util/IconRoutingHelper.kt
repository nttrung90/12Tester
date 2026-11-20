package com.ntt.crosstest.util

import android.net.Uri
import android.util.Log
import com.ntt.crosstest.BuildConfig
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Định tuyến Icon công khai có phiên bản qua CDN kèm dự phòng Firebase (E05).
 *
 * Ràng buộc kiến trúc (F14 / R2-13):
 * 1. Chỉ áp dụng cho app_icons/ công khai (immutable, cache 1 năm). Tuyệt đối KHÔNG mở public cho screenshot!
 * 2. Mặc định cờ isCdnRoutingEnabled là FALSE và cdnHost rỗng (PoC mode, CDN chưa deploy thực tế).
 * 3. Hỗ trợ fallback tự động: Nếu CDN trả 404, 5xx hoặc lỗi mạng, tự động chuyển về Firebase Storage origin.
 * 4. Bounded fallback: Chỉ fallback tối đa 1 lần về origin, ngăn vòng lặp vô hạn (infinite retry loop).
 * 5. Parse đúng bucket allowlist và prefix path; screenshot có chuỗi query "app_icons" không được phép định tuyến.
 */
object IconRoutingHelper {

    private const val TAG = "IconRoutingHelper"

    private val ALLOWED_STORAGE_HOSTS = setOf(
        "firebasestorage.googleapis.com",
        "storage.googleapis.com",
        "cross-testers.firebasestorage.app",
        "firebasestorage.app"
    )
    private const val ALLOWED_BUCKET = "cross-testers.firebasestorage.app"

    @Volatile
    var isCdnRoutingEnabled: Boolean = false

    @Volatile
    var cdnHost: String = ""

    private val fallbackAttemptedUrls = Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )

    /**
     * Tách biệt chính xác bucket và objectPath từ URL theo URL schema / anchored regex.
     * Ngăn chặn triệt để query/path spoofing (ví dụ: tên bucket xuất hiện trong object name).
     */
    private fun parseBucketAndObject(url: String?): Pair<String, String>? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return null
            val rawPath = uri.path ?: return null

            when {
                host == ALLOWED_BUCKET -> {
                    val cleanPath = rawPath.replace("%2F", "/").replace("%2f", "/")
                    val obj = when {
                        cleanPath.startsWith("/o/") -> cleanPath.removePrefix("/o/")
                        cleanPath.startsWith("/$ALLOWED_BUCKET/") -> cleanPath.removePrefix("/$ALLOWED_BUCKET/")
                        else -> cleanPath.trimStart('/')
                    }
                    Pair(host, obj)
                }
                host == "firebasestorage.googleapis.com" || host == "firebasestorage.app" -> {
                    // Regex neo chặt theo URL schema Firebase: /v0/b/<bucket>/o/<object> hoặc /b/<bucket>/o/<object>
                    val match = Regex("""^/(?:v0/)?b/([^/]+)/o/(.+)$""").find(rawPath) ?: return null
                    val bucket = match.groupValues[1]
                    val rawObj = match.groupValues[2]
                    val obj = rawObj.replace("%2F", "/").replace("%2f", "/")
                    Pair(bucket, obj)
                }
                host == "storage.googleapis.com" -> {
                    // Regex neo chặt theo URL schema GCS: /storage/v1/b/<bucket>/o/<object>, /b/<bucket>/o/<object> hoặc /<bucket>/<object>
                    val matchB = Regex("""^/(?:storage/v1/)?b/([^/]+)/o/(.+)$""").find(rawPath)
                    if (matchB != null) {
                        val bucket = matchB.groupValues[1]
                        val rawObj = matchB.groupValues[2]
                        val obj = rawObj.replace("%2F", "/").replace("%2f", "/")
                        Pair(bucket, obj)
                    } else {
                        val matchDirect = Regex("""^/([^/]+)/(.+)$""").find(rawPath) ?: return null
                        val bucket = matchDirect.groupValues[1]
                        val rawObj = matchDirect.groupValues[2]
                        val obj = rawObj.replace("%2F", "/").replace("%2f", "/")
                        Pair(bucket, obj)
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Xác định xem URL có phải là icon công khai hợp lệ thuộc Firebase Storage hay không.
     * Kiểm tra đúng bucket được cấu hình và object path; cấm nhận diện nhầm từ query string spoofing hay bucket lạ.
     */
    fun isPublicIconUrl(url: String?): Boolean {
        val (bucket, objectPath) = parseBucketAndObject(url) ?: return false
        if (bucket != ALLOWED_BUCKET) return false
        return objectPath.startsWith("app_icons/") && objectPath.length > "app_icons/".length
    }

    /**
     * Tạo URL CDN từ URL gốc Firebase Storage.
     * Nếu không bật CDN routing, cdnHost rỗng hoặc không phải icon, trả về URL gốc.
     */
    fun resolveIconUrl(firebaseUrl: String?): String {
        if (firebaseUrl.isNullOrBlank()) return ""
        if (!isCdnRoutingEnabled || cdnHost.isBlank()) {
            return firebaseUrl
        }
        val (bucket, objectPath) = parseBucketAndObject(firebaseUrl) ?: return firebaseUrl
        if (bucket != ALLOWED_BUCKET || !objectPath.startsWith("app_icons/")) {
            return firebaseUrl
        }

        return try {
            val filename = objectPath.removePrefix("app_icons/").substringBefore('?').substringBefore('#')
            val encodedFilename = Uri.encode(filename)
            "https://${cdnHost.trim().trimEnd('/')}/app_icons/$encodedFilename"
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "resolveIconUrl failed, fallback to origin: ${e.message}")
            firebaseUrl
        }
    }

    /**
     * Nhận diện xem một lỗi request đến CDN có cần kích hoạt fallback về Firebase origin hay không.
     * Bounded fallback: chặn lặp nếu đã từng fallback cho URL này.
     */
    fun shouldFallbackToOrigin(failedUrl: String?, httpStatusCode: Int): Boolean {
        if (failedUrl.isNullOrBlank() || cdnHost.isBlank()) return false
        val uri = try { Uri.parse(failedUrl) } catch (_: Exception) { return false }
        if (uri.host != cdnHost.trim().trimEnd('/')) return false
        if (fallbackAttemptedUrls.contains(failedUrl)) return false // Bounded: không loop
        // 404 (chưa sync lên CDN), 5xx (CDN server error) hoặc IO timeout (0) -> fallback
        return httpStatusCode == 404 || (httpStatusCode in 500..599) || httpStatusCode == 0
    }

    fun shouldFallbackToOrigin(failedUrl: String?, error: Throwable? = null): Boolean {
        if (failedUrl.isNullOrBlank() || cdnHost.isBlank()) return false
        val uri = try { Uri.parse(failedUrl) } catch (_: Exception) { return false }
        if (uri.host != cdnHost.trim().trimEnd('/')) return false
        if (fallbackAttemptedUrls.contains(failedUrl)) return false
        return true
    }

    fun recordFallbackOccurred(failedCdnUrl: String) {
        fallbackAttemptedUrls.add(failedCdnUrl)
    }

    fun resetFallbackTracking() {
        fallbackAttemptedUrls.clear()
    }
}
