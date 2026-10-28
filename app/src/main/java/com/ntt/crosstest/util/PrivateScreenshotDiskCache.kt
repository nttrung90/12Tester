package com.ntt.crosstest.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.ntt.crosstest.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Giao thức lấy quyền truy cập media ngắn hạn (E04).
 */
interface MediaAccessGrantProvider {
    suspend fun getMediaAccessGrant(reportId: String): MediaAccessGrantResult?
    fun getCurrentAuthToken(): String?
}

@kotlinx.serialization.Serializable
data class MediaAccessGrantResult(
    val success: Boolean,
    @kotlinx.serialization.SerialName("report_id") val reportId: String? = null,
    val role: String? = null,
    @kotlinx.serialization.SerialName("screenshot_url") val screenshotUrl: String? = null,
    @kotlinx.serialization.SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String? = null,
    @kotlinx.serialization.SerialName("expires_at_epoch") val expiresAtEpoch: Long? = null,
    @kotlinx.serialization.SerialName("access_grant_token") val accessGrantToken: String? = null,
)

/**
 * Chính sách cấu hình bộ nhớ đệm riêng tư (E03).
 */
object ScreenshotCachePolicy {
    /**
     * Mặc định TẮT (false) đến khi retention/access contract và rollout được xác nhận trên production.
     * Chỉ cho phép bật có kiểm soát qua feature flag trong phiên thử nghiệm nội bộ.
     */
    @Volatile
    var isPrivateDiskCacheEnabled: Boolean = false

    /**
     * Cờ kích hoạt Media Access Gateway (E04). Mặc định TẮT (false) đến khi backend gateway sẵn sàng.
     */
    @Volatile
    var isMediaGatewayEnabled: Boolean = false

    /**
     * Base URL của Media Gateway endpoint. Mặc định rỗng khi chưa cấu hình.
     */
    @Volatile
    var mediaGatewayBaseUrl: String = ""

    /**
     * Provider cấp quyền truy cập RPC và JWT hiện hành của phiên người dùng.
     */
    @Volatile
    var mediaAccessGrantProvider: MediaAccessGrantProvider? = null

    const val MAX_DISK_CACHE_BYTES = 50L * 1024L * 1024L // 50 MiB
    const val MAX_DISK_TTL_MINUTES = 30L // Giới hạn tối đa 30 phút trong phiên
}

/**
 * Module cache riêng tư ngắn hạn trên đĩa cho screenshot (E03).
 *
 * Ràng buộc bảo mật & dữ liệu nghiêm ngặt (F07–F10):
 * 1. Scope rõ ràng theo: accountId / generation / variant / urlHash. Từ chối khi account rỗng hoặc "default" (F08).
 * 2. Phải có expiry tin cậy từ server (created_at + 30 ngày và media_expired != null). Thiếu hoặc lỗi phải fail-closed (F09).
 * 3. Tuyệt đối KHÔNG ghi đĩa nếu server response có header "no-store" hoặc "no-cache".
 * 4. Tôn trọng max-age từ server, không biến max-age=60s thành 30 phút (F09).
 * 5. Bù lệch đồng hồ (clock skew) nhất quán giữa server Date và client system time (F09).
 * 6. Decode ARGB_8888 đồng nhất với network decode để bảo toàn dHash basis (F10).
 * 7. Hỗ trợ xóa nguyên tử file cụ thể khi phát hiện clear/logout giữa chừng (F07).
 */
object PrivateScreenshotDiskCache {

    private const val TAG = "PrivateDiskCache"
    private const val DIRECTORY_NAME = "private_screenshot_cache"
    private const val MAGIC_HEADER = 0x50534331 // "PSC1" (Private Screenshot Cache v1)
    private val isCleaningUp = AtomicBoolean(false)

    fun cacheDir(context: Context): File =
        File(context.noBackupFilesDir, DIRECTORY_NAME).apply { mkdirs() }

    fun accountDir(context: Context, accountId: String): File =
        File(cacheDir(context), sanitizePathSegment(accountId)).apply { mkdirs() }

    fun generationDir(context: Context, accountId: String, generation: Long, variant: ScreenshotVariant): File =
        File(accountDir(context, accountId), "gen_${generation}/${variant.name.lowercase()}").apply { mkdirs() }

    private fun sanitizePathSegment(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

    fun hashUrl(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(url.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun parseMaxAgeSeconds(cacheControlHeader: String?, ageHeader: String? = null): Long? {
        // 1. Kiểm tra tính hợp lệ của Age header nếu được cung cấp (RFC 9111 Section 5.1)
        var ageSec = 0L
        if (!ageHeader.isNullOrBlank()) {
            val trimmedAge = ageHeader.trim().trim('"', '\'')
            if (!trimmedAge.matches(Regex("""^\d+$"""))) {
                return 0L // Age không phải số nguyên không âm hợp lệ -> fail-closed
            }
            val parsedAge = trimmedAge.toLongOrNull() ?: return 0L // Tràn số Long -> fail-closed
            if (parsedAge < 0L) return 0L
            ageSec = parsedAge
        }

        if (cacheControlHeader.isNullOrBlank()) return null

        // 2. Parse các directive trong Cache-Control (RFC 9111 Section 5.2.2.1)
        val directives = cacheControlHeader.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val maxAgeDirectives = directives.filter {
            val name = it.substringBefore('=').trim()
            name.equals("max-age", ignoreCase = true)
        }

        // Nếu trong header có từ khóa max-age nhưng không tách được directive hợp lệ
        if (maxAgeDirectives.isEmpty()) {
            if (cacheControlHeader.contains("max-age", ignoreCase = true)) {
                return 0L // Directive max-age bị malformed -> fail-closed
            }
            return null
        }

        // Nếu có nhiều directive max-age (duplicate/conflicting) -> fail-closed
        if (maxAgeDirectives.size > 1) {
            return 0L
        }

        val directive = maxAgeDirectives.first()
        if (!directive.contains('=')) {
            return 0L
        }

        val rawValue = directive.substringAfter('=').trim().trim('"', '\'')
        if (!rawValue.matches(Regex("""^\d+$"""))) {
            return 0L // Giá trị max-age không phải 1*DIGIT (ví dụ: not-a-number, 60oops)
        }

        val maxAgeSec = rawValue.toLongOrNull() ?: return 0L // Tràn số Long -> fail-closed
        if (maxAgeSec <= 0L) return 0L

        // Age lớn hơn hoặc bằng max-age nghĩa là response đã stale -> fail-closed
        if (ageSec >= maxAgeSec) {
            return 0L
        }

        val freshnessSec = maxAgeSec - ageSec
        return minOf(freshnessSec, 30L * 24 * 60 * 60)
    }

    /**
     * Ghi file vào cache riêng tư trên đĩa.
     * Trả về true nếu ghi thành công, false nếu bị chặn bởi chính sách hoặc lỗi.
     */
    fun put(
        context: Context,
        accountId: String,
        generation: Long,
        variant: ScreenshotVariant,
        url: String,
        bitmapBytes: ByteArray,
        cacheControlHeader: String?,
        reportCreatedAt: String?,
        mediaExpired: Boolean?,
        serverDateHeaderEpochMs: Long? = null,
        ageHeader: String? = null,
        isSessionValid: (() -> Boolean)? = null,
    ): Boolean {
        // 1. Kiểm tra cờ tính năng
        if (!ScreenshotCachePolicy.isPrivateDiskCacheEnabled) return false

        // 2. F08: Ràng buộc tài khoản - từ chối ghi khi accountId rỗng hoặc chưa xác thực
        if (accountId.isBlank() || accountId == "default") {
            if (BuildConfig.DEBUG) Log.d(TAG, "put_skipped: unauthenticated or default accountId")
            return false
        }

        // 3. RÀNG BUỘC CỐT LÕI: Tuyệt đối không lưu nếu response có header "no-store" hoặc "no-cache"
        if (cacheControlHeader != null) {
            if (cacheControlHeader.contains("no-store", ignoreCase = true) ||
                cacheControlHeader.contains("no-cache", ignoreCase = true)
            ) {
                if (BuildConfig.DEBUG) Log.d(TAG, "put_skipped: cache-control directive forbids caching")
                return false
            }
        }

        // 4. F09: Phải có hợp đồng expiry và trạng thái media tin cậy từ server. Thiếu -> Fail-closed!
        if (reportCreatedAt.isNullOrBlank() || mediaExpired == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "put_skipped: missing trusted expiry or mediaExpired contract")
            return false
        }
        if (mediaExpired) return false

        val createdInstant = try {
            Instant.parse(reportCreatedAt)
        } catch (_: Exception) {
            return false
        }

        val nowMs = System.currentTimeMillis()

        // 5. F09: Tính TTL tôn trọng server max-age và bù lệch đồng hồ (clock skew)
        val maxAgeSec = parseMaxAgeSeconds(cacheControlHeader, ageHeader)
        if (maxAgeSec != null && maxAgeSec <= 0L) {
            return false
        }
        val ttlMs = if (maxAgeSec != null) {
            minOf(maxAgeSec * 1000L, ScreenshotCachePolicy.MAX_DISK_TTL_MINUTES * 60 * 1000L)
        } else {
            ScreenshotCachePolicy.MAX_DISK_TTL_MINUTES * 60 * 1000L
        }

        val clockSkewMs = if (serverDateHeaderEpochMs != null && serverDateHeaderEpochMs > 0) {
            serverDateHeaderEpochMs - nowMs
        } else {
            0L
        }

        // Chuyển mốc tạo report sang hệ quy chiếu thời gian local của client
        val createdInLocalMs = createdInstant.toEpochMilli() - clockSkewMs
        val retentionCutoffInLocalMs = createdInLocalMs + (30L * 24 * 60 * 60 * 1000L)

        if (nowMs >= retentionCutoffInLocalMs) {
            return false // Báo cáo đã quá hạn 30 ngày
        }

        val ttlCutoffInLocalMs = nowMs + ttlMs
        val finalExpiryMs = minOf(retentionCutoffInLocalMs, ttlCutoffInLocalMs)

        // 6. Kiểm tra dung lượng và dọn dẹp nếu vượt ngân sách 50MB
        enforceCapacity(context, bitmapBytes.size.toLong())

        val targetDir = generationDir(context, accountId, generation, variant)
        val targetFile = File(targetDir, "${hashUrl(url)}.bin")
        val tempFile = File(targetDir, "${targetFile.name}.tmp_${System.nanoTime()}")

        return try {
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.writeInt(MAGIC_HEADER)
                raf.writeLong(finalExpiryMs)
                raf.writeLong(nowMs) // cachedAt
                raf.writeInt(bitmapBytes.size)
                raf.write(bitmapBytes)
            }
            if (isSessionValid != null && !isSessionValid()) {
                tempFile.delete()
                false
            } else {
                try {
                    java.nio.file.Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                    true
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                    true
                }
            }
        } catch (e: Exception) {
            tempFile.delete()
            if (BuildConfig.DEBUG) Log.w(TAG, "put_failed: ${e.message}")
            false
        }
    }

    /**
     * Đọc Bitmap từ cache đĩa riêng tư nếu còn hợp lệ.
     */
    fun get(
        context: Context,
        accountId: String,
        generation: Long,
        variant: ScreenshotVariant,
        url: String,
        currentReportCreatedAt: String? = null,
        currentMediaExpired: Boolean? = null,
    ): Bitmap? {
        if (!ScreenshotCachePolicy.isPrivateDiskCacheEnabled) return null
        if (accountId.isBlank() || accountId == "default") return null

        val targetFile = File(generationDir(context, accountId, generation, variant), "${hashUrl(url)}.bin")
        if (!targetFile.isFile) return null

        // F09: Nếu server báo media đã expired, xóa ngay file cache vật lý trên đĩa
        if (currentMediaExpired == true) {
            targetFile.delete()
            return null
        }

        val nowMs = System.currentTimeMillis()

        return try {
            RandomAccessFile(targetFile, "r").use { raf ->
                val magic = raf.readInt()
                if (magic != MAGIC_HEADER) {
                    targetFile.delete()
                    return null
                }
                val expiryMs = raf.readLong()
                val cachedAtMs = raf.readLong()
                val dataSize = raf.readInt()

                // Kiểm tra hết hạn TTL hoặc mốc server
                if (nowMs >= expiryMs || nowMs - cachedAtMs > (ScreenshotCachePolicy.MAX_DISK_TTL_MINUTES * 60 * 1000L)) {
                    targetFile.delete()
                    return null
                }

                // Kiểm tra lại với metadata mới nhất của report (nếu được truyền vào)
                if (!currentReportCreatedAt.isNullOrBlank()) {
                    try {
                        val created = Instant.parse(currentReportCreatedAt)
                        val cutoff = Instant.now().minus(java.time.Duration.ofDays(30))
                        if (created.isBefore(cutoff)) {
                            targetFile.delete()
                            return null
                        }
                    } catch (_: Exception) {
                    }
                }

                if (dataSize <= 0 || dataSize > 20 * 1024 * 1024) {
                    targetFile.delete()
                    return null
                }

                val bytes = ByteArray(dataSize)
                raf.readFully(bytes)

                // F10: Decode ARGB_8888 đồng nhất với network decode để bảo toàn dHash
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (bitmap != null) {
                    targetFile.setLastModified(nowMs)
                }
                bitmap
            }
        } catch (e: Exception) {
            targetFile.delete()
            if (BuildConfig.DEBUG) Log.w(TAG, "get_failed: ${e.message}")
            null
        }
    }

    /**
     * Xóa nguyên tử một tệp cache cụ thể (dùng khi race invalidated sau clear).
     */
    fun deleteSpecificFile(
        context: Context,
        accountId: String,
        generation: Long,
        variant: ScreenshotVariant,
        url: String
    ): Boolean {
        return try {
            val targetFile = File(generationDir(context, accountId, generation, variant), "${hashUrl(url)}.bin")
            targetFile.delete()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Dọn dẹp các file quá hạn khi ứng dụng khởi động hoặc resume.
     */
    fun cleanupOnStartupOrResume(context: Context) {
        if (!isCleaningUp.compareAndSet(false, true)) return
        try {
            val root = cacheDir(context)
            val nowMs = System.currentTimeMillis()
            root.walkTopDown().filter { it.isFile && it.extension == "bin" }.forEach { file ->
                try {
                    RandomAccessFile(file, "r").use { raf ->
                        if (raf.readInt() == MAGIC_HEADER) {
                            val expiryMs = raf.readLong()
                            if (nowMs >= expiryMs) {
                                file.delete()
                            }
                        } else {
                            file.delete()
                        }
                    }
                } catch (_: Exception) {
                    file.delete()
                }
            }
        } finally {
            isCleaningUp.set(false)
        }
    }

    /**
     * Xóa sạch cache của một generation cụ thể cho tài khoản chỉ định.
     */
    fun clearForGeneration(context: Context, accountId: String, generation: Long) {
        try {
            val genDir = File(accountDir(context, accountId), "gen_$generation")
            genDir.deleteRecursively()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "clearForGeneration failed: ${e.message}")
        }
    }

    /**
     * Xóa sạch cache của một tài khoản khi đăng xuất (logout).
     */
    fun clearForAccount(context: Context, accountId: String) {
        try {
            val dir = accountDir(context, accountId)
            dir.deleteRecursively()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "clearForAccount failed: ${e.message}")
        }
    }

    /**
     * Xóa toàn bộ disk cache riêng tư.
     */
    fun clearAll(context: Context) {
        try {
            cacheDir(context).deleteRecursively()
            cacheDir(context).mkdirs()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "clearAll failed: ${e.message}")
        }
    }

    private fun enforceCapacity(context: Context, incomingBytes: Long) {
        try {
            val files = cacheDir(context).walkTopDown().filter { it.isFile && it.extension == "bin" }.toList()
            var currentTotal = files.sumOf { it.length() }
            if (currentTotal + incomingBytes <= ScreenshotCachePolicy.MAX_DISK_CACHE_BYTES) return

            // Evict theo LRU (sắp xếp theo lastModified tăng dần)
            val sorted = files.sortedBy { it.lastModified() }
            for (file in sorted) {
                val len = file.length()
                if (file.delete()) {
                    currentTotal -= len
                    if (currentTotal + incomingBytes <= ScreenshotCachePolicy.MAX_DISK_CACHE_BYTES * 0.8) {
                        break // Đã giải phóng đủ không gian (xuống dưới 80%)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
