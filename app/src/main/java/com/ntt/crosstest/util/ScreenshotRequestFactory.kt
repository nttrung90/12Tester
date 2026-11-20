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
        attachmentId: String? = null,
        provider: String? = null,
        viewExpiresAt: String? = null,
    ): ScreenshotDescriptor? {
        if (url.isNullOrBlank()) return null
        if (descriptor != null) return descriptor
        if (report != null) {
            val matchingAtt = if (attachmentId != null) {
                report.attachments.firstOrNull { it.id == attachmentId }
            } else {
                report.attachments.firstOrNull { it.screenshotUrl == url || it.thumbnailUrl == url }
            }
            val realAttachmentId = (matchingAtt?.id ?: attachmentId)?.takeIf { it != report.id }
            val fallback = if (variant == ScreenshotVariant.THUMBNAIL) {
                matchingAtt?.screenshotUrl ?: (if (realAttachmentId == null) report.screenshotUrl else null)
            } else null

            val effCreatedAt: String?
            val effMediaExpired: Boolean
            val effProvider: String?
            val effViewExpiresAt: String?

            if (matchingAtt != null) {
                // Attachment riêng: DÙNG METADATA RIÊNG (a.createdAt), KHÔNG ĐỂ CHA HẾT HẠN CHẶN CON CÒN HẠN
                effCreatedAt = matchingAtt.createdAt ?: report.createdAt
                effMediaExpired = matchingAtt.mediaExpired ?: matchingAtt.isExpired
                // Tránh fallback parent.storageProvider cho mixed provider; infer theo URL hoặc provider riêng
                val explicitProvider = matchingAtt.storageProvider ?: matchingAtt.provider ?: provider
                effProvider = if (!explicitProvider.isNullOrBlank()) {
                    explicitProvider
                } else {
                    if (com.ntt.crosstest.data.MediaExpiryPolicy.isSupabaseStorage(url)) "supabase" else "gcs"
                }
                // Deadline riêng của attachment: không kế thừa report.viewExpiresAt của cha
                effViewExpiresAt = matchingAtt.viewExpiresAt ?: viewExpiresAt
            } else {
                // Ảnh chính của report
                effCreatedAt = report.createdAt
                effMediaExpired = report.mediaExpired ?: report.isScreenshotExpired
                effProvider = report.storageProvider ?: provider
                effViewExpiresAt = report.viewExpiresAt ?: viewExpiresAt
            }

            return ScreenshotDescriptor(
                url = url,
                createdAt = effCreatedAt,
                mediaExpired = effMediaExpired,
                variant = variant,
                fallbackUrl = fallback,
                reportId = report.id,
                attachmentId = realAttachmentId,
                provider = effProvider,
                viewExpiresAt = effViewExpiresAt,
            )
        }
        return ScreenshotDescriptor(
            url = url,
            variant = variant,
            attachmentId = attachmentId,
            provider = provider,
            viewExpiresAt = viewExpiresAt,
        )
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
        attachmentId: String? = null,
    ): Any? {
        if (model == null) return null
        if (model is ImageRequest) return model
        if (model !is String) return model

        val matchesSecondary = report?.attachments?.any { it.screenshotUrl == model || it.thumbnailUrl == model } == true
        val targetUrl = if (variant == ScreenshotVariant.THUMBNAIL && report != null && !report.thumbnailUrl.isNullOrBlank() && attachmentId == null && !matchesSecondary) {
            report.thumbnailUrl
        } else {
            model
        }

        val desc = createDescriptor(targetUrl, report, descriptor, variant, attachmentId)
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
