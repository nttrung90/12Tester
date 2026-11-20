package com.ntt.crosstest.data

import com.ntt.crosstest.BuildConfig
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * Chính sách thuần xác định thời hạn hiển thị của ảnh media (screenshot/attachment).
 * Dùng chung nhất quán cho Models (TesterReport/ReportAttachment), ScreenshotManager,
 * RequestFactory và các UI Lightbox/Comparison dialogs.
 */
object MediaExpiryPolicy {

    val SUPABASE_RETENTION: Duration = Duration.ofHours(216) // 9 ngày (216 giờ)
    val GOOGLE_RETENTION: Duration = Duration.ofDays(30)    // 30 ngày

    /**
     * Xác định xem ảnh có URL và metadata tương ứng đã hết hạn xem hay chưa.
     *
     * Tiêu chí cốt lõi:
     * 1. URL rỗng/trắng: coi như hết hạn (true).
     * 2. mediaExpired == true: đánh dấu hết hạn ngay lập tức (true).
     * 3. viewExpiresAt: hạn authoritative nếu hợp lệ:
     *    - Nếu parse hợp lệ và now >= exp: đã hết hạn (true).
     *    - Nếu parse hợp lệ và now < exp: CÒN HẠN (false), TUYỆT ĐỐI KHÔNG xét createdAt 216h cũ.
     * 4. Provider:
     *    - Nếu có giá trị explicit (ví dụ "gcs"): TUYỆT ĐỐI KHÔNG bị ghi đè bởi filename chứa "supabase".
     *    - Nếu explicit "supabase": áp dụng 216h.
     *    - Nếu rỗng: fallback nhận diện Supabase chỉ khi host là trusted Supabase origin VÀ path chứa bucket /report-screenshots/.
     * 5. Cutoff với createdAt:
     *    - So sánh phải >=: now >= created + retention (tức là !now.isBefore(created.plus(retention))).
     */
    fun isExpired(
        url: String?,
        createdAt: String?,
        mediaExpired: Boolean? = null,
        provider: String? = null,
        viewExpiresAt: String? = null,
        now: Instant = Instant.now(),
    ): Boolean {
        if (url.isNullOrBlank()) return true
        if (mediaExpired == true) return true

        // 1. Authoritative viewExpiresAt
        if (!viewExpiresAt.isNullOrBlank()) {
            try {
                val exp = Instant.parse(viewExpiresAt)
                // So sánh >=: nếu now >= exp thì hết hạn, nếu now < exp thì CÒN HẠN authoritative
                return !now.isBefore(exp)
            } catch (_: Exception) {
                // Parse lỗi: fallback kiểm tra theo createdAt
            }
        }

        // 2. Kiểm tra theo createdAt
        if (createdAt.isNullOrBlank()) return false

        return try {
            val created = Instant.parse(createdAt)
            val isSupabase = isSupabaseStorage(url, provider)
            val duration = if (isSupabase) SUPABASE_RETENTION else GOOGLE_RETENTION
            val deadline = created.plus(duration)
            // So sánh phải >=: now >= deadline (tức là !now.isBefore(deadline))
            !now.isBefore(deadline)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Xác định URL có thuộc Supabase Storage hay không.
     * - Nếu có provider: ưu tiên tuyệt đối giá trị provider.
     * - Nếu không có provider: fallback kiểm tra exact trusted host + path chứa bucket report-screenshots.
     */
    fun isSupabaseStorage(url: String, provider: String? = null): Boolean {
        if (!provider.isNullOrBlank()) {
            return provider.equals("supabase", ignoreCase = true)
        }
        return isTrustedSupabaseStorageUrl(url)
    }

    fun extractCanonicalOrigin(urlString: String?): String? {
        if (urlString.isNullOrBlank()) return null
        return try {
            val uri = URI(urlString)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (!uri.rawUserInfo.isNullOrBlank()) return null

            val isLocalhost = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
            if (scheme != "https" && !(scheme == "http" && isLocalhost)) {
                return null
            }
            val rawPort = uri.port
            val defaultPort = if (scheme == "https") 443 else 80
            val effectivePort = if (rawPort != -1) rawPort else defaultPort
            if (effectivePort == defaultPort) "$scheme://$host" else "$scheme://$host:$effectivePort"
        } catch (_: Exception) {
            null
        }
    }

    fun isTrustedSupabaseStorageUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            val targetOrigin = extractCanonicalOrigin(url) ?: return false
            val trustedOrigin = extractCanonicalOrigin(BuildConfig.SUPABASE_URL) ?: return false

            // So khớp exact origin scheme + host + effectivePort với BuildConfig.SUPABASE_URL
            if (targetOrigin != trustedOrigin) return false

            val cleanPath = (uri.path ?: "").trimStart('/')
            // Bắt buộc đúng path storage/v1/object/(authenticated|public)/report-screenshots
            cleanPath.startsWith("storage/v1/object/authenticated/report-screenshots/") ||
            cleanPath.startsWith("storage/v1/object/public/report-screenshots/") ||
            cleanPath == "storage/v1/object/authenticated/report-screenshots" ||
            cleanPath == "storage/v1/object/public/report-screenshots"
        } catch (_: Exception) {
            false
        }
    }
}
