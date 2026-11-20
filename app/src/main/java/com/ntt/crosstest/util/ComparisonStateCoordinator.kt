package com.ntt.crosstest.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.ntt.crosstest.data.TesterReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Điều phối tác vụ so sánh ảnh chi tiết nhằm chống race condition khi:
 * 1. Chuyển đổi nhanh giữa các cặp báo cáo (superseded operation).
 * 2. Thay đổi phiên / đăng xuất (session generation mismatch).
 * 3. Hết hạn ảnh động (30 ngày hoặc media_expired) trong lúc tải.
 * 4. Tombstone 404/410 phát sinh giữa chừng.
 * 5. finally của tác vụ cũ không được đè cờ loading của tác vụ mới.
 */
object ComparisonStateCoordinator {

    private const val LOCAL_DECODE_MAX_DIMENSION = 1024

    /** Read dimensions first so a gallery image cannot allocate its full pixel buffer. */
    internal fun decodeLocalBitmap(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > LOCAL_DECODE_MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private val operationCounter = AtomicLong(0L)

    data class ComparisonExecution(
        val operationId: Long,
        val sessionGeneration: Long,
        val report1Id: String?,
        val report2Id: String?,
        val url1: String?,
        val url2: String?,
    )

    fun startOperation(
        report1: TesterReport?,
        report2: TesterReport?,
        url1: String?,
        url2: String?,
    ): ComparisonExecution {
        val opId = operationCounter.incrementAndGet()
        val gen = ScreenshotManager.sessionGeneration.get()
        return ComparisonExecution(
            operationId = opId,
            sessionGeneration = gen,
            report1Id = report1?.id,
            report2Id = report2?.id,
            url1 = url1,
            url2 = url2,
        )
    }

    fun isCurrent(execution: ComparisonExecution): Boolean {
        if (operationCounter.get() != execution.operationId) return false
        if (ScreenshotManager.sessionGeneration.get() != execution.sessionGeneration) return false
        return true
    }

    suspend fun executeDetailedComparison(
        context: Context,
        execution: ComparisonExecution,
        report1: TesterReport?,
        report2: TesterReport?,
        image1: Any?,
        image2: Any?,
    ): DetailedImageComparator.DetailedComparisonResult? = withContext(Dispatchers.IO) {
        if (!isCurrent(execution)) return@withContext null
        if (image1 == null || image2 == null) return@withContext null

        val url1 = if (image1 is Uri) null else (image1 as? String ?: execution.url1)
        val url2 = if (image2 is Uri) null else (image2 as? String ?: execution.url2)
        val descriptor1 = url1?.let { ScreenshotRequestFactory.createDescriptor(it, report1) }
        val descriptor2 = url2?.let { ScreenshotRequestFactory.createDescriptor(it, report2) }
        // The selected attachment controls its own lifetime; local selections have no remote expiry.
        if (ScreenshotManager.isExpired(descriptor1) || ScreenshotManager.isExpired(descriptor2)) {
            return@withContext null
        }
        if (url1 != null && ScreenshotManager.is404Tombstoned(url1)) return@withContext null
        if (url2 != null && ScreenshotManager.is404Tombstoned(url2)) return@withContext null

        coroutineContext.ensureActive()

        var localB1: Bitmap? = null
        var localB2: Bitmap? = null

        try {
            val b1 = when (image1) {
                is Bitmap -> if (!image1.isRecycled) image1 else null
                is String -> ScreenshotManager.getOrFetchBitmap(context, image1, "compare_detailed", descriptor = descriptor1)
                is Uri -> {
                    localB1 = try {
                        decodeLocalBitmap(context, image1)
                    } catch (_: Exception) { null }
                    localB1
                }
                else -> null
            }

            coroutineContext.ensureActive()
            if (!isCurrent(execution)) return@withContext null

            val b2 = when (image2) {
                is Bitmap -> if (!image2.isRecycled) image2 else null
                is String -> ScreenshotManager.getOrFetchBitmap(context, image2, "compare_detailed", descriptor = descriptor2)
                is Uri -> {
                    localB2 = try {
                        decodeLocalBitmap(context, image2)
                    } catch (_: Exception) { null }
                    localB2
                }
                else -> null
            }

            coroutineContext.ensureActive()

            // 2. Kiểm tra tính hiện hành và hết hạn động sau khi fetch
            if (!isCurrent(execution)) return@withContext null
            if (ScreenshotManager.isExpired(descriptor1) || ScreenshotManager.isExpired(descriptor2)) {
                return@withContext null
            }
            if (url1 != null && ScreenshotManager.is404Tombstoned(url1)) return@withContext null
            if (url2 != null && ScreenshotManager.is404Tombstoned(url2)) return@withContext null

            if (b1 == null || b2 == null) return@withContext null

            val isSameUrl = (image1 is String && image2 is String && image1 == image2)

            coroutineContext.ensureActive()
            val result = DetailedImageComparator.compareBitmaps(b1, b2, isSameUrl)

            // 3. Kiểm tra lần cuối trước khi publish
            if (!isCurrent(execution) || ScreenshotManager.isExpired(descriptor1) || ScreenshotManager.isExpired(descriptor2)) return@withContext null
            return@withContext result
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } finally {
            localB1?.recycle()
            localB2?.recycle()
        }
    }
}
