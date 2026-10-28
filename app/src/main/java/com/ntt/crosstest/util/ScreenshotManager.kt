package com.ntt.crosstest.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.data.TesterReport
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class ScreenshotVariant {
    FULL,
    THUMBNAIL
}

data class ScreenshotDescriptor(
    val url: String,
    val createdAt: String? = null,
    val mediaExpired: Boolean? = null,
    val variant: ScreenshotVariant = ScreenshotVariant.FULL,
    val fallbackUrl: String? = null,
    val reportId: String? = null,
)

class ScreenshotExpiredException(val sanitizedUrl: String) :
    IllegalStateException("Screenshot expired: $sanitizedUrl")

class ScreenshotUnavailableException(val sanitizedUrl: String) :
    IOException("Screenshot unavailable (404): $sanitizedUrl")

class ScreenshotPermissionDeniedException(val sanitizedUrl: String) :
    IOException("Screenshot access forbidden (403): $sanitizedUrl")

/**
 * Quản lý vòng đời ảnh báo cáo, RAM cache ngắn hạn, single-flight tải ảnh và ranh giới phiên (E01a–E01d).
 *
 * Tiêu chí cốt lõi:
 * 1. REPORT_CACHE_CONTROL = "private, no-store": Disk cache KHÔNG lưu screenshot.
 * 2. RAM reuse: Giữ tối đa 24 MiB bitmap phần mềm trong phiên xem.
 * 3. Single-flight với hủy OkHttp khi không còn waiter: lifecycle thuộc về InFlightTask,
 *    creator bị hủy khi followers còn chờ không làm xóa flight, không làm tạo GET trùng.
 * 4. Giới hạn response-body: đọc theo chunk, dừng ngay tại 2.5 MB cho cả stream unknown-length,
 *    giới hạn kích thước bitmap trước khi decode.
 * 5. Atomic generation guard: check generation và publish atomic với clear; cache-hit và 404 tombstone kiểm tra generation.
 * 6. Expiry re-check: lặp lại kiểm tra hết hạn sau khi có response mạng trước khi publish và return.
 * 7. Phân biệt rõ 403 (lỗi quyền), 404 (tombstone không retry), hết hạn (>=30 ngày hoặc media_expired).
 */
object ScreenshotManager {

    private const val TAG = "ScreenshotManager"

    private const val MAX_RAM_CACHE_BYTES = 24 * 1024 * 1024
    const val MAX_BODY_BYTES = 2_500_000L // 2.5 MB
    private const val CHUNK_SIZE = 16_384L // 16 KB

    private val stateLock = Any()

    private val sessionCache = object : LruCache<String, Bitmap>(MAX_RAM_CACHE_BYTES) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount.coerceAtLeast(1)
        }
    }

    // Lưu trữ metadata ngày tạo và cờ hết hạn theo URL (tối đa 200 báo cáo gần nhất)
    private val descriptorCache = LruCache<String, ScreenshotDescriptor>(200)

    // Tombstone 404 có giới hạn và scope theo generation
    private val tombstoned404Cache = LruCache<String, Long>(100)

    val sessionGeneration = AtomicLong(0L)
    @Volatile
    var currentAccountId: String = ""
    private var pendingPurgeAccountId: String? = null

    fun onUserSessionChanged(newUserId: String, context: Context? = null) {
        synchronized(stateLock) {
            val accountToPurge = pendingPurgeAccountId
            if (context != null && !accountToPurge.isNullOrBlank() && accountToPurge != "default" && accountToPurge != newUserId) {
                pendingPurgeAccountId = null
                PrivateScreenshotDiskCache.clearForAccount(context, accountToPurge)
            }
            if (currentAccountId == newUserId && newUserId.isNotBlank()) return
            val oldAccount = currentAccountId.ifBlank { accountToPurge ?: "" }
            currentAccountId = newUserId
            if (context == null && oldAccount.isNotBlank() && oldAccount != "default" && oldAccount != newUserId) {
                pendingPurgeAccountId = oldAccount
            } else {
                pendingPurgeAccountId = null
            }
            sessionGeneration.incrementAndGet()
            cancelInFlightLocked()
            sessionCache.evictAll()
            descriptorCache.evictAll()
            tombstoned404Cache.evictAll()
            if (context != null && oldAccount.isNotBlank() && oldAccount != "default" && oldAccount != newUserId) {
                PrivateScreenshotDiskCache.clearForAccount(context, oldAccount)
            }
        }
    }

    fun onUserSignedOut(context: Context? = null) {
        synchronized(stateLock) {
            val accountToPurge = pendingPurgeAccountId
            if (context != null && !accountToPurge.isNullOrBlank() && accountToPurge != "default") {
                pendingPurgeAccountId = null
                PrivateScreenshotDiskCache.clearForAccount(context, accountToPurge)
            }
            val oldAccount = currentAccountId.ifBlank { accountToPurge ?: "" }
            currentAccountId = ""
            sessionGeneration.incrementAndGet()
            cancelInFlightLocked()
            sessionCache.evictAll()
            descriptorCache.evictAll()
            tombstoned404Cache.evictAll()
            if (context != null && oldAccount.isNotBlank() && oldAccount != "default") {
                pendingPurgeAccountId = null
                PrivateScreenshotDiskCache.clearForAccount(context, oldAccount)
            } else if (context == null && oldAccount.isNotBlank() && oldAccount != "default") {
                pendingPurgeAccountId = oldAccount
            }
        }
    }

    private fun cancelInFlightLocked() {
        for ((_, task) in inFlightRequests) {
            task.call.cancel()
            task.deferred.cancel()
        }
        inFlightRequests.clear()
    }

    private class InFlightTask(
        val flightKey: String,
        val call: Call,
        val generation: Long,
        val deferred: Deferred<Bitmap?>,
    ) {
        val activeWaiters = AtomicInteger(1)

        fun addWaiter() {
            activeWaiters.incrementAndGet()
        }

        fun removeWaiter() {
            if (activeWaiters.decrementAndGet() <= 0) {
                call.cancel()
                deferred.cancel()
            }
        }
    }

    private val inFlightRequests = ConcurrentHashMap<String, InFlightTask>()

    @Volatile
    var customHttpClient: OkHttpClient? = null

    private val defaultHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(ImageInstrumentation.createTrackingInterceptor())
            .build()
    }

    val httpClient: OkHttpClient
        get() = customHttpClient ?: defaultHttpClient

    fun isReportScreenshotUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains("/reports%2F") || url.contains("/reports/") || url.contains("reports%2F")
    }

    fun registerReportMetadata(report: TesterReport?) {
        if (report == null || report.screenshotUrl.isNullOrBlank()) return
        registerReportMetadata(report.screenshotUrl, report.createdAt, report.mediaExpired)
    }

    fun registerReportMetadata(url: String, createdAt: String?, mediaExpired: Boolean?) {
        if (url.isBlank()) return
        synchronized(stateLock) {
            descriptorCache.put(url, ScreenshotDescriptor(url, createdAt, mediaExpired))
        }
    }

    fun isExpired(descriptor: ScreenshotDescriptor?): Boolean {
        if (descriptor == null) return false
        return isExpired(descriptor.url, descriptor.createdAt, descriptor.mediaExpired)
    }

    fun isExpired(url: String?, createdAt: String? = null, mediaExpired: Boolean? = null): Boolean {
        if (url.isNullOrBlank()) return true

        synchronized(stateLock) {
            if (mediaExpired == true) return true

            val cachedDesc = descriptorCache.get(url)
            if (cachedDesc?.mediaExpired == true) return true
            if (!cachedDesc?.createdAt.isNullOrBlank()) {
                try {
                    val created = Instant.parse(cachedDesc.createdAt)
                    val cutoff = Instant.now().minus(Duration.ofDays(30))
                    if (created.isBefore(cutoff)) return true
                } catch (_: Exception) {}
            }

            val effectiveCreatedAt = createdAt ?: cachedDesc?.createdAt
            val effectiveMediaExpired = mediaExpired ?: cachedDesc?.mediaExpired

            if (effectiveMediaExpired == true) return true

            if (!effectiveCreatedAt.isNullOrBlank()) {
                try {
                    val created = Instant.parse(effectiveCreatedAt)
                    val cutoff = Instant.now().minus(Duration.ofDays(30))
                    if (created.isBefore(cutoff)) return true
                } catch (_: Exception) {}
            }
        }

        return false
    }

    fun isExpired(report: TesterReport?): Boolean {
        if (report == null) return false
        if (report.isScreenshotExpired) return true
        return isExpired(report.screenshotUrl, report.createdAt, report.mediaExpired)
    }

    fun is404Tombstoned(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        synchronized(stateLock) {
            val gen = tombstoned404Cache.get(url)
            return gen != null && gen == sessionGeneration.get()
        }
    }

    fun mark404Unavailable(url: String, gen: Long = sessionGeneration.get()) {
        synchronized(stateLock) {
            if (gen == sessionGeneration.get()) {
                tombstoned404Cache.put(url, gen)
            }
        }
    }

    fun getCachedBitmap(url: String?, expectedGeneration: Long = sessionGeneration.get()): Bitmap? {
        if (url.isNullOrBlank()) return null
        synchronized(stateLock) {
            if (sessionGeneration.get() != expectedGeneration) return null
            val cached = sessionCache.get(url)
            if (cached != null && !cached.isRecycled) {
                return cached
            }
        }
        return null
    }

    /**
     * Đọc body theo chunk tối đa maxBytes. Dừng đọc và ngắt ngay khi vượt hạn mức,
     * không đọc toàn bộ stream nếu dung lượng vượt 2.5MB (Issue A).
     */
    fun readBoundedBodyBytes(source: BufferedSource, maxBytes: Long = MAX_BODY_BYTES): ByteArray? {
        val buffer = Buffer()
        var totalRead = 0L
        while (true) {
            val toRead = minOf(CHUNK_SIZE, maxBytes - totalRead + 1L)
            val read = source.read(buffer, toRead)
            if (read == -1L) break
            totalRead += read
            if (totalRead > maxBytes) {
                return null
            }
        }
        return buffer.readByteArray()
    }

    /**
     * Tải hoặc lấy bitmap từ RAM với single-flight, hủy OkHttp call khi không còn waiter,
     * và atomic generation guard để chặn rò rỉ phiên.
     */
    suspend fun getOrFetchBitmap(
        context: Context,
        url: String,
        purpose: String = "preview",
        report: TesterReport? = null,
        descriptor: ScreenshotDescriptor? = null,
    ): Bitmap? {
        if (url.isBlank()) return null

        val effectiveDescriptor = descriptor ?: if (report != null) {
            ScreenshotRequestFactory.createDescriptor(url, report, variant = descriptor?.variant ?: ScreenshotVariant.FULL)
        } else null

        val effCreatedAt = report?.createdAt ?: effectiveDescriptor?.createdAt
        val effMediaExpired = report?.mediaExpired ?: effectiveDescriptor?.mediaExpired

        if (effCreatedAt != null || effMediaExpired != null) {
            registerReportMetadata(url, effCreatedAt, effMediaExpired)
        }

        if (report?.isScreenshotExpired == true || isExpired(url, effCreatedAt, effMediaExpired) || is404Tombstoned(url)) {
            return null
        }

        val genAtStart = sessionGeneration.get()
        val accountIdAtStart = currentAccountId
        val variant = effectiveDescriptor?.variant ?: ScreenshotVariant.FULL

        // E04: Khi Media Gateway bật nhưng chưa có cấu hình Base URL -> Fail closed, không fallback về URL gốc
        if (ScreenshotCachePolicy.isMediaGatewayEnabled && ScreenshotCachePolicy.mediaGatewayBaseUrl.isBlank()) {
            return null
        }

        // 1. Kiểm tra RAM cache đồng bộ
        synchronized(stateLock) {
            if (sessionGeneration.get() == genAtStart && currentAccountId == accountIdAtStart) {
                sessionCache.get(url)?.let { cached ->
                    if (!cached.isRecycled && !isExpired(url, effCreatedAt, effMediaExpired)) {
                        ImageInstrumentation.recordMemoryHit()
                        return cached
                    }
                }
            }
        }

        // 1b. Kiểm tra Private Disk Cache nếu chính sách bật (E03)
        if (ScreenshotCachePolicy.isPrivateDiskCacheEnabled && accountIdAtStart.isNotBlank() && accountIdAtStart != "default") {
            val diskCached = PrivateScreenshotDiskCache.get(
                context = context,
                accountId = accountIdAtStart,
                generation = genAtStart,
                variant = variant,
                url = url,
                currentReportCreatedAt = effCreatedAt,
                currentMediaExpired = effMediaExpired,
            )
            if (diskCached != null) {
                synchronized(stateLock) {
                    if (genAtStart == sessionGeneration.get() && currentAccountId == accountIdAtStart && !diskCached.isRecycled) {
                        sessionCache.put(url, diskCached)
                        ImageInstrumentation.recordDiskHit()
                        return diskCached
                    }
                }
                // F07: Nếu phiên đã bị hủy trong lúc đọc đĩa, không trả về bitmap
                return null
            }
        }

        val effectiveUrl = resolveEffectiveUrlAsync(url, effectiveDescriptor)
        val flightKey = "$genAtStart:$url"

        // 2. Single-flight với lifecycle thuộc về InFlightTask (Issue B)
        val inFlightTask = synchronized(stateLock) {
            if (sessionGeneration.get() != genAtStart || currentAccountId != accountIdAtStart) return null
            sessionCache.get(url)?.let { cached ->
                if (!cached.isRecycled && !isExpired(url, effCreatedAt, effMediaExpired)) {
                    ImageInstrumentation.recordMemoryHit()
                    return cached
                }
            }

            val existing = inFlightRequests[flightKey]
            if (existing != null) {
                existing.addWaiter()
                existing
            } else {
                val requestBuilder = Request.Builder()
                    .url(effectiveUrl)
                    .header("X-Image-Purpose", purpose)

                val grantProvider = ScreenshotCachePolicy.mediaAccessGrantProvider
                if (ScreenshotCachePolicy.isMediaGatewayEnabled && grantProvider != null) {
                    val authToken = grantProvider.getCurrentAuthToken()
                    if (!authToken.isNullOrBlank()) {
                        requestBuilder.header("Authorization", "Bearer $authToken")
                    }
                }
                val request = requestBuilder.build()
                val call = httpClient.newCall(request)

                lateinit var taskRef: InFlightTask

                val deferred = CoroutineScope(Dispatchers.IO).async {
                    try {
                        call.execute().use { response ->
                            val code = response.code
                            if (code == 404) {
                                mark404Unavailable(url, genAtStart)
                                return@use null
                            }

                            if (code == 410) {
                                mark404Unavailable(url, genAtStart)
                                throw ScreenshotExpiredException(ImageInstrumentation.sanitizeUrl(url))
                            }

                            if (code == 403) {
                                throw ScreenshotPermissionDeniedException(ImageInstrumentation.sanitizeUrl(url))
                            }

                            if (!response.isSuccessful) {
                                return@use null
                            }

                            val body = response.body
                            val contentLength = body.contentLength()
                            if (contentLength > MAX_BODY_BYTES) {
                                Log.w(TAG, "Screenshot body exceeded limit ($contentLength > $MAX_BODY_BYTES)")
                                return@use null
                            }

                            val bytes = readBoundedBodyBytes(body.source(), MAX_BODY_BYTES)
                            if (bytes == null || bytes.isEmpty()) {
                                return@use null
                            }

                            // Giới hạn kích thước bitmap trước khi decode (Issue A)
                            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0 ||
                                boundsOpts.outWidth > 4096 || boundsOpts.outHeight > 4096
                            ) {
                                Log.w(TAG, "Bitmap dimensions invalid/too large: ${boundsOpts.outWidth}x${boundsOpts.outHeight}")
                                return@use null
                            }

                            val opts = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

                            // Kiểm tra lại expiry trước khi publish/return sau network (Issue C)
                            if (isExpired(url, effCreatedAt, effMediaExpired) || isExpired(url)) {
                                return@use null
                            }

                            synchronized(stateLock) {
                                if (genAtStart == sessionGeneration.get() && bitmap != null && !bitmap.isRecycled) {
                                    sessionCache.put(url, bitmap)
                                }
                            }

                            // E03/F07: Lưu vào disk cache riêng tư với kiểm tra generation & accountId nghiêm ngặt
                            if (ScreenshotCachePolicy.isPrivateDiskCacheEnabled && bitmap != null && accountIdAtStart.isNotBlank() && accountIdAtStart != "default") {
                                val currentGen = sessionGeneration.get()
                                if (currentGen == genAtStart && currentAccountId == accountIdAtStart) {
                                    val cacheControl = response.header("Cache-Control")
                                    val ageHeader = response.header("Age")
                                    val serverDateMs = response.header("Date")?.let {
                                        try {
                                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(it, java.time.Instant::from).toEpochMilli()
                                        } catch (_: Exception) { null }
                                    }
                                    val wrote = PrivateScreenshotDiskCache.put(
                                        context = context,
                                        accountId = accountIdAtStart,
                                        generation = genAtStart,
                                        variant = variant,
                                        url = url,
                                        bitmapBytes = bytes,
                                        cacheControlHeader = cacheControl,
                                        reportCreatedAt = effCreatedAt,
                                        mediaExpired = effMediaExpired,
                                        serverDateHeaderEpochMs = serverDateMs,
                                        ageHeader = ageHeader,
                                        isSessionValid = {
                                            synchronized(stateLock) {
                                                sessionGeneration.get() == genAtStart && currentAccountId == accountIdAtStart
                                            }
                                        },
                                    )
                                    // F07: Nếu clear/logout xảy ra trong lúc ghi đĩa, xóa ngay file vừa ghi
                                    if (wrote) {
                                        synchronized(stateLock) {
                                            if (sessionGeneration.get() != genAtStart || currentAccountId != accountIdAtStart) {
                                                PrivateScreenshotDiskCache.deleteSpecificFile(
                                                    context = context,
                                                    accountId = accountIdAtStart,
                                                    generation = genAtStart,
                                                    variant = variant,
                                                    url = url
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            synchronized(stateLock) {
                                if (sessionGeneration.get() != genAtStart || currentAccountId != accountIdAtStart) return@use null
                            }
                            bitmap
                        }
                    } finally {
                        // Task tự dọn chính nó khỏi map khi kết thúc, không phụ thuộc việc creator hay follower cancel (Issue B)
                        synchronized(stateLock) {
                            inFlightRequests.remove(flightKey, taskRef)
                        }
                    }
                }

                taskRef = InFlightTask(flightKey, call, genAtStart, deferred)
                inFlightRequests[flightKey] = taskRef
                taskRef
            }
        }

        try {
            val result = inFlightTask.deferred.await()

            // Kiểm tra lại generation, accountId và expiry trước khi trả kết quả cho caller (Issue C)
            synchronized(stateLock) {
                if (genAtStart == sessionGeneration.get() && currentAccountId == accountIdAtStart && result != null && !result.isRecycled) {
                    if (isExpired(url, effCreatedAt, effMediaExpired) || isExpired(url)) {
                        return null
                    }
                    return result
                } else {
                    return null
                }
            }
        } catch (ce: CancellationException) {
            // Caller này rời đi: giảm activeWaiters, chỉ cancel call khi không còn ai chờ (Issue B)
            inFlightTask.removeWaiter()
            throw ce
        } catch (e: Exception) {
            if (e is ScreenshotPermissionDeniedException || e is ScreenshotExpiredException) throw e
            return null
        }
    }

    /**
     * Dọn sạch cache RAM, hủy tất cả in-flight OkHttp calls và tăng sessionGeneration.
     */
    fun clear(context: Context? = null) {
        val oldGen: Long
        val oldAccount: String
        synchronized(stateLock) {
            oldGen = sessionGeneration.getAndIncrement()
            oldAccount = currentAccountId
            cancelInFlightLocked()
            sessionCache.evictAll()
            descriptorCache.evictAll()
            tombstoned404Cache.evictAll()
        }
        if (context != null) {
            val targetAccount = oldAccount.ifBlank { pendingPurgeAccountId ?: "" }
            if (targetAccount.isNotBlank() && targetAccount != "default") {
                PrivateScreenshotDiskCache.clearForAccount(context, targetAccount)
            }
        }
    }

    suspend fun resolveEffectiveUrlAsync(rawUrl: String, descriptor: ScreenshotDescriptor? = null): String {
        if (!ScreenshotCachePolicy.isMediaGatewayEnabled || ScreenshotCachePolicy.mediaGatewayBaseUrl.isBlank()) {
            return rawUrl
        }
        if (!isReportScreenshotUrl(rawUrl)) {
            return rawUrl
        }
        val reportId = descriptor?.reportId
            ?: extractReportIdFromUrl(rawUrl)
            ?: return rawUrl
        val variant = if (descriptor?.variant == ScreenshotVariant.THUMBNAIL) "thumb" else "full"
        val provider = ScreenshotCachePolicy.mediaAccessGrantProvider
        if (provider != null) {
            val grant = provider.getMediaAccessGrant(reportId)
            if (grant != null && grant.success) {
                val signedUrl = if (variant == "thumb") {
                    grant.thumbnailUrl ?: grant.screenshotUrl
                } else {
                    grant.screenshotUrl
                }
                if (!signedUrl.isNullOrBlank()) return signedUrl
            }
        }
        val base = ScreenshotCachePolicy.mediaGatewayBaseUrl.trimEnd('/')
        return "$base?report_id=$reportId&variant=$variant"
    }

    fun resolveEffectiveUrl(rawUrl: String, descriptor: ScreenshotDescriptor? = null): String {
        if (!ScreenshotCachePolicy.isMediaGatewayEnabled || ScreenshotCachePolicy.mediaGatewayBaseUrl.isBlank()) {
            return rawUrl
        }
        if (!isReportScreenshotUrl(rawUrl)) {
            return rawUrl
        }
        val reportId = descriptor?.reportId
            ?: extractReportIdFromUrl(rawUrl)
            ?: return rawUrl
        val variant = if (descriptor?.variant == ScreenshotVariant.THUMBNAIL) "thumb" else "full"
        val base = ScreenshotCachePolicy.mediaGatewayBaseUrl.trimEnd('/')
        return "$base?report_id=$reportId&variant=$variant"
    }

    private fun extractReportIdFromUrl(url: String): String? {
        return try {
            val clean = url.substringBefore('?').substringBefore('#')
            val filename = clean.substringAfterLast('/').substringAfterLast("%2F")
            val baseName = filename.substringBeforeLast('.')
            val id = if (baseName.startsWith("thumb_")) baseName.removePrefix("thumb_") else baseName
            if (id.length >= 8) id else null
        } catch (_: Exception) {
            null
        }
    }

    fun resolveDisplayUrl(report: TesterReport?, isFull: Boolean = false): String? {
        if (report == null) return null
        if (isFull) return report.screenshotUrl
        return report.thumbnailUrl ?: report.screenshotUrl
    }
}
