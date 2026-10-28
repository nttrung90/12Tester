package com.ntt.crosstest.util

import android.content.Context
import coil.request.ImageRequest
import com.ntt.crosstest.data.TesterReport

/**
 * Request factory thống nhất cho tất cả các luồng xem và xử lý ảnh screenshot (E01a–E01d):
 * Card preview -> Fullscreen zoom -> Compare dialog -> dHash duplicate calculation.
 *
 * Gắn trực tiếp `ScreenshotDescriptor` qua tag của ImageRequest ngay khi khởi tạo,
 * thay vì phụ thuộc vào LaunchedEffect chạy trễ sau render.
 */
object ScreenshotRequestFactory {

    fun createDescriptor(
        url: String?,
        report: TesterReport? = null,
        descriptor: ScreenshotDescriptor? = null,
        variant: ScreenshotVariant = ScreenshotVariant.FULL,
    ): ScreenshotDescriptor? {
        if (url.isNullOrBlank()) return null
        if (descriptor != null) return descriptor
        if (report != null) {
            return ScreenshotDescriptor(
                url = url,
                createdAt = report.createdAt,
                mediaExpired = report.mediaExpired ?: report.isScreenshotExpired,
                variant = variant,
                fallbackUrl = if (variant == ScreenshotVariant.THUMBNAIL) report.screenshotUrl else null,
                reportId = report.id,
            )
        }
        return ScreenshotDescriptor(url = url, variant = variant)
    }

    /**
     * Tạo ImageRequest hoặc giữ nguyên model nếu là Uri/File cục bộ.
     */
    fun createRequest(
        context: Context,
        model: Any?,
        report: TesterReport? = null,
        descriptor: ScreenshotDescriptor? = null,
        variant: ScreenshotVariant = ScreenshotVariant.FULL,
        crossfade: Boolean = true,
    ): Any? {
        if (model == null) return null
        if (model is ImageRequest) return model
        if (model !is String) return model

        val targetUrl = if (variant == ScreenshotVariant.THUMBNAIL && report != null && !report.thumbnailUrl.isNullOrBlank()) {
            report.thumbnailUrl
        } else {
            model
        }

        val desc = createDescriptor(targetUrl, report, descriptor, variant)
        val builder = ImageRequest.Builder(context)
            .data(targetUrl)

        if (desc != null) {
            builder.tag(ScreenshotDescriptor::class.java, desc)
        }

        if (crossfade) {
            builder.crossfade(true)
        }

        return builder.build()
    }

    /**
     * Tạo ImageRequest từ TesterReport.
     */
    fun createRequest(
        context: Context,
        report: TesterReport?,
        variant: ScreenshotVariant = ScreenshotVariant.FULL,
        crossfade: Boolean = true,
    ): ImageRequest? {
        if (report == null) return null
        val targetUrl = if (variant == ScreenshotVariant.THUMBNAIL && !report.thumbnailUrl.isNullOrBlank()) {
            report.thumbnailUrl
        } else {
            report.screenshotUrl
        }
        if (targetUrl.isNullOrBlank()) return null
        return createRequest(
            context = context,
            model = targetUrl,
            report = report,
            variant = variant,
            crossfade = crossfade,
        ) as? ImageRequest
    }
}
