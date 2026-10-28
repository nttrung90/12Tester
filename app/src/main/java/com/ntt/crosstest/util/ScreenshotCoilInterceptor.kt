package com.ntt.crosstest.util

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Coil Interceptor chặn tải trùng và điều phối cache RAM cho screenshot (E01a–E01d).
 *
 * - Bỏ qua fetch mạng nếu ảnh đã hết hạn (>= 30 ngày hoặc media_expired).
 * - Bỏ qua fetch mạng nếu URL đã tombstone 404.
 * - Phục vụ trực tiếp từ ScreenshotManager RAM session cache nếu đã có bitmap và session generation khớp.
 * - Chuyển sang ScreenshotManager single-flight để không tải lặp khi có nhiều callers đồng thời.
 * - Phân biệt rõ ràng lỗi 403 (ScreenshotPermissionDeniedException) với 404/hết hạn.
 * - Rethrow CancellationException để coroutine cancellation được truyền nguyên vẹn.
 * - Các ảnh khác (app_icons, avatar, local URI) tiếp tục đi theo Coil pipeline mặc định.
 */
class ScreenshotCoilInterceptor(
    private val context: Context,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data

        if (data is String && ScreenshotManager.isReportScreenshotUrl(data)) {
            val descriptor = request.tags.tag(ScreenshotDescriptor::class.java)
            val genAtStart = ScreenshotManager.sessionGeneration.get()

            // 1. Kiểm tra hết hạn báo cáo (>= 30 ngày hoặc media_expired)
            if (ScreenshotManager.isExpired(data, descriptor?.createdAt, descriptor?.mediaExpired)) {
                return ErrorResult(
                    drawable = null,
                    request = request,
                    throwable = ScreenshotExpiredException(ImageInstrumentation.sanitizeUrl(data))
                )
            }

            // 2. Tái sử dụng RAM session cache cho chính URL này
            val cached = ScreenshotManager.getCachedBitmap(data, genAtStart)
            if (cached != null && !cached.isRecycled && !ScreenshotManager.isExpired(data, descriptor?.createdAt, descriptor?.mediaExpired)) {
                if (ScreenshotManager.sessionGeneration.get() == genAtStart) {
                    ImageInstrumentation.recordMemoryHit()
                    return SuccessResult(
                        drawable = BitmapDrawable(context.resources, cached),
                        request = request,
                        dataSource = DataSource.MEMORY_CACHE,
                    )
                }
            }

            // 3. F06: Xử lý trường hợp URL đã bị tombstone 404
            val isTombstoned = ScreenshotManager.is404Tombstoned(data)
            val hasThumbnailFallback = descriptor?.variant == ScreenshotVariant.THUMBNAIL && !descriptor.fallbackUrl.isNullOrBlank()

            if (isTombstoned && !hasThumbnailFallback) {
                return ErrorResult(
                    drawable = null,
                    request = request,
                    throwable = ScreenshotUnavailableException(ImageInstrumentation.sanitizeUrl(data))
                )
            }

            // Nếu thumbnail đã bị 404 tombstone và có fallback: kiểm tra RAM cache của fallback trước
            if (isTombstoned && hasThumbnailFallback) {
                val fallbackUrl = descriptor.fallbackUrl
                val cachedFallback = ScreenshotManager.getCachedBitmap(fallbackUrl, genAtStart)
                if (cachedFallback != null && !cachedFallback.isRecycled && !ScreenshotManager.isExpired(fallbackUrl, descriptor.createdAt, descriptor.mediaExpired)) {
                    if (ScreenshotManager.sessionGeneration.get() == genAtStart) {
                        ImageInstrumentation.recordMemoryHit()
                        return SuccessResult(
                            drawable = BitmapDrawable(context.resources, cachedFallback),
                            request = request,
                            dataSource = DataSource.MEMORY_CACHE,
                        )
                    }
                }
            }

            // 4. Tải qua single-flight manager
            try {
                var bitmap: android.graphics.Bitmap? = null
                if (!isTombstoned) {
                    bitmap = ScreenshotManager.getOrFetchBitmap(
                        context = context,
                        url = data,
                        purpose = "preview",
                        descriptor = descriptor,
                    )
                }

                // E02/F06 Fallback: Nếu thumbnail không tồn tại (404/null) và có fallbackUrl sang ảnh full
                if (bitmap == null && hasThumbnailFallback) {
                    val fallbackUrl = descriptor.fallbackUrl
                    if (!ScreenshotManager.isExpired(fallbackUrl, descriptor.createdAt, descriptor.mediaExpired)) {
                        val fallbackDesc = descriptor.copy(
                            url = fallbackUrl,
                            variant = ScreenshotVariant.FULL,
                            fallbackUrl = null,
                        )
                        bitmap = ScreenshotManager.getOrFetchBitmap(
                            context = context,
                            url = fallbackUrl,
                            purpose = "preview_fallback_full",
                            descriptor = fallbackDesc,
                        )
                    }
                }

                if (ScreenshotManager.sessionGeneration.get() == genAtStart && bitmap != null && !bitmap.isRecycled) {
                    return SuccessResult(
                        drawable = BitmapDrawable(context.resources, bitmap),
                        request = request,
                        dataSource = DataSource.NETWORK,
                    )
                }

                if (ScreenshotManager.is404Tombstoned(data)) {
                    return ErrorResult(
                        drawable = null,
                        request = request,
                        throwable = ScreenshotUnavailableException(ImageInstrumentation.sanitizeUrl(data))
                    )
                }

                return ErrorResult(
                    drawable = null,
                    request = request,
                    throwable = IOException("Screenshot load failed or session invalidated for ${ImageInstrumentation.sanitizeUrl(data)}")
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: ScreenshotPermissionDeniedException) {
                return ErrorResult(
                    drawable = null,
                    request = request,
                    throwable = e
                )
            } catch (e: Exception) {
                return ErrorResult(
                    drawable = null,
                    request = request,
                    throwable = e
                )
            }
        }

        // Không phải screenshot báo cáo (app icon, avatar Google...) -> pipeline chuẩn của Coil
        return chain.proceed(request)
    }
}
