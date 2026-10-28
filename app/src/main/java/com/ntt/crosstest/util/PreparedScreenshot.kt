package com.ntt.crosstest.util

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Ảnh đã nén, sẵn sàng để kiểm tra trùng và upload.
 *
 * File nằm trong bộ nhớ riêng của app (không backup lên cloud) để:
 * - preview không cần đọc lại content Uri;
 * - upload retry không cần user chọn lại ảnh;
 * - không phải nén lại ảnh nhiều lần.
 */
data class PreparedScreenshot(
    val uploadId: String,
    val file: File,
    val imageHash: String,
    val sizeBytes: Long,
    val contentType: String,
    val thumbnailFile: File? = null,
    val thumbnailSizeBytes: Long? = null,
)

object PreparedScreenshotCache {

    private const val DIRECTORY = "prepared_report_images"
    private const val MAX_AGE_MS = 48L * 60L * 60L * 1000L

    fun newUploadId(): String = UUID.randomUUID().toString()

    fun directory(context: Context): File =
        File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }

    fun newFile(context: Context, uploadId: String, extension: String = "jpg"): File =
        File(directory(context), "$uploadId.$extension")

    fun newThumbnailFile(context: Context, uploadId: String, extension: String = "jpg"): File =
        File(directory(context), "thumb_$uploadId.$extension")

    /** Dọn ảnh đã chọn nhưng không nộp sau 48h. */
    fun cleanup(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        directory(context).listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    fun clear(context: Context) {
        directory(context).deleteRecursively()
        directory(context).mkdirs()
    }

    fun delete(file: File?) {
        if (file == null) return
        if (file.isFile) file.delete()
    }
}
