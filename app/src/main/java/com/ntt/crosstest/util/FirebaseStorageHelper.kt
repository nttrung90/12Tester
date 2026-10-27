package com.ntt.crosstest.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.ktx.storage
import com.ntt.crosstest.R
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.data.ReportSubmitPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object FirebaseStorageHelper {

    private const val TAG = "FirebaseStorageHelper"
    private const val REPORT_MAX_DIMENSION = 1080
    private const val REPORT_QUALITY = 80
    /** Storage rules giới hạn 2MB; chừa biên an toàn để không bị reject. */
    private const val REPORT_MAX_BYTES = 1_900_000
    private const val ICON_MAX_DIMENSION = 256
    private const val ICON_QUALITY = 90
    private const val IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"

    /**
     * Nén ảnh nộp đúng một lần rồi ghi ra file local:
     * decode sample -> scale max 1080 -> xoay EXIF -> JPEG q80.
     *
     * Hash được tính trên chính bytes đã ghi, nên hash luôn mô tả đúng
     * artifact sẽ được upload. File local cho phép preview và retry mà
     * không cần đọc lại content Uri.
     */
    suspend fun prepareScreenshot(
        context: Context,
        imageUri: Uri,
        uploadId: String = PreparedScreenshotCache.newUploadId(),
    ): PreparedScreenshot = withContext(Dispatchers.IO) {
        val decoded = decodeSampledBitmapFromUri(
            context = context,
            uri = imageUri,
            maxDimension = REPORT_MAX_DIMENSION,
            config = Bitmap.Config.RGB_565,
        )
        var working = decoded
        try {
            // Scale trước khi rotate để tránh tạo bitmap xoay full-size.
            val scaled = scaleDownToMax(working, REPORT_MAX_DIMENSION)
            if (scaled !== working) {
                working.recycle()
                working = scaled
            }

            val oriented = applyExifOrientation(context, imageUri, working)
            val bytes = try {
                var compressed = compressBitmap(
                    bitmap = oriented,
                    maxDimension = REPORT_MAX_DIMENSION,
                    quality = REPORT_QUALITY,
                    format = Bitmap.CompressFormat.JPEG,
                )
                // Ảnh quá nhiễu có thể vượt 2MB dù đã scale 1080; hạ quality
                // rồi hạ kích thước để không bị Storage rules từ chối.
                if (compressed.size > REPORT_MAX_BYTES) {
                    compressed = compressBitmap(
                        bitmap = oriented,
                        maxDimension = REPORT_MAX_DIMENSION,
                        quality = 60,
                        format = Bitmap.CompressFormat.JPEG,
                    )
                }
                if (compressed.size > REPORT_MAX_BYTES) {
                    compressed = compressBitmap(
                        bitmap = oriented,
                        maxDimension = 720,
                        quality = 70,
                        format = Bitmap.CompressFormat.JPEG,
                    )
                }
                if (compressed.size > REPORT_MAX_BYTES) {
                    throw IllegalStateException("Bitmap compression failed: image too large")
                }
                compressed
            } finally {
                if (oriented !== working) oriented.recycle()
            }

            val compressedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException(context.getString(R.string.error_image_read_failed))
            val hash = try {
                ImageHashHelper.computeDHash(compressedBitmap)
            } finally {
                compressedBitmap.recycle()
            }

            val file = PreparedScreenshotCache.newFile(context, uploadId, "jpg")
            try {
                file.outputStream().use { it.write(bytes) }
            } catch (e: Exception) {
                file.delete()
                throw e
            }

            PreparedScreenshot(
                uploadId = uploadId,
                file = file,
                imageHash = hash,
                sizeBytes = bytes.size.toLong(),
                contentType = "image/jpeg",
            )
        } finally {
            if (!working.isRecycled) working.recycle()
        }
    }

    /**
     * Retry only transient URL errors; upload success does not require a metadata delay.
     */
    private suspend fun StorageReference.awaitDownloadUrlWithRetry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500L,
    ): String = resolveReportUrl(maxAttempts, initialDelayMs) {
        val started = SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG) Log.d(TAG, "req=${name.take(8)} url_start")
        try { downloadUrl.await().toString() } finally {
            if (BuildConfig.DEBUG) Log.d(TAG, "req=${name.take(8)} url_end elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        }
    }

    /**
     * Upload ảnh đã nén. Path cố định theo uploadId để retry không tạo thêm object rác.
     * Storage rules chỉ cho phép create (không cho update/overwrite), do đó nếu object
     * đã tồn tại từ lần trước nhưng client chưa nhận được kết quả, hàm sẽ thử đọc lại
     * downloadUrl của object cũ thay vì fail cả bài nộp.
     */
    suspend fun uploadReportScreenshot(
        prepared: PreparedScreenshot,
        campaignId: String,
        userId: String,
    ): String = withContext(Dispatchers.IO) {
        val storageRef = Firebase.storage.reference
            .child("reports/$campaignId/$userId/${prepared.uploadId}.jpg")

        val metadata = StorageMetadata.Builder()
            .setContentType(prepared.contentType)
            .setCacheControl(IMMUTABLE_CACHE_CONTROL)
            .build()
        val upload = storageRef.putFile(Uri.fromFile(prepared.file), metadata)
        try {
            val started = SystemClock.elapsedRealtime()
            if (BuildConfig.DEBUG) Log.d(TAG, "req=${prepared.uploadId.take(8)} put_file_start")
            try { upload.await() } finally {
                if (BuildConfig.DEBUG) Log.d(TAG, "req=${prepared.uploadId.take(8)} put_file_end elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            }
        } catch (e: CancellationException) {
            upload.cancel()
            throw e
        } catch (e: Exception) {
            ReportSubmitPolicy.cancellationCause(e)?.let { upload.cancel(); throw it }
            // Storage rules chỉ cho create (không cho update). Nếu lần upload
            // trước đã tạo object nhưng response bị mất, downloadUrl sẽ trả về
            // object cũ; dùng lại thay vì fail cả bài nộp.
            val existingUrl = try {
                storageRef.awaitDownloadUrlWithRetry(maxAttempts = 2, initialDelayMs = 300L)
            } catch (ce: CancellationException) {
                throw ce
            } catch (recoveryError: Exception) {
                throw reportRecoveryFailure(e, recoveryError)
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "req=${prepared.uploadId.take(8)} existing_object_recovered")
            return@withContext existingUrl
        }

        // Resolve the URL without repeating a successful upload.
        storageRef.awaitDownloadUrlWithRetry(maxAttempts = 3, initialDelayMs = 500L)
    }

    /**
     * Uploads an app icon Bitmap directly to Firebase Storage (public URL).
     * WebP giữ được alpha và nhỏ hơn PNG q90 (quality của PNG bị Android bỏ qua).
     */
    @Suppress("DEPRECATION")
    suspend fun uploadAppIconBitmap(
        bitmap: Bitmap,
        packageName: String,
    ): String = withContext(Dispatchers.IO) {
        val cleanPkg = packageName.ifBlank { "app" }.replace(Regex("[^a-zA-Z0-9_.]"), "_")
        // Tên unique: không ghi đè object cũ, tránh làm hỏng iconUrl (chứa
        // download token) của các campaign đã tạo trước đó.
        val filename = "${cleanPkg}_${PreparedScreenshotCache.newUploadId()}.webp"
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        val bytes = compressBitmap(
            bitmap = bitmap,
            maxDimension = ICON_MAX_DIMENSION,
            quality = ICON_QUALITY,
            format = format,
        )
        val storageRef = Firebase.storage.reference.child("app_icons/$filename")

        val metadata = StorageMetadata.Builder()
            .setContentType("image/webp")
            .setCacheControl(IMMUTABLE_CACHE_CONTROL)
            .build()
        storageRef.putBytes(bytes, metadata).await()

        storageRef.downloadUrl.await().toString()
    }

    /**
     * Uploads an app icon from Uri directly to Firebase Storage.
     * Icon dùng ARGB_8888 để không mất nền trong suốt.
     */
    suspend fun uploadAppIconUri(
        context: Context,
        imageUri: Uri,
        packageName: String,
    ): String = withContext(Dispatchers.IO) {
        val bitmap = decodeSampledBitmapFromUri(
            context = context,
            uri = imageUri,
            maxDimension = ICON_MAX_DIMENSION,
            config = Bitmap.Config.ARGB_8888,
        )
        val oriented = applyExifOrientation(context, imageUri, bitmap)
        try {
            uploadAppIconBitmap(oriented, packageName)
        } finally {
            if (oriented !== bitmap) oriented.recycle()
            bitmap.recycle()
        }
    }

    /**
     * BitmapFactory không tự áp dụng EXIF orientation; ảnh chụp từ camera/gallery
     * sẽ bị lưu nằm ngang nếu bỏ qua bước xoay này (EXIF cũng bị mất khi re-encode).
     */
    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(
                -1f,
                1f,
                bitmap.width / 2f,
                bitmap.height / 2f,
            )
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(
                1f,
                -1f,
                bitmap.width / 2f,
                bitmap.height / 2f,
            )
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) rotated else bitmap
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        maxDimension: Int,
        config: Bitmap.Config,
    ): Bitmap {
        // 1. First decode with inJustDecodeBounds=true to check dimensions
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        // 2. Calculate inSampleSize from the longest edge. This avoids decoding
        // a huge panorama (e.g. 20000x2000) at full resolution.
        val inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxDimension)

        // 3. Decode bitmap with inSampleSize set.
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = config
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: throw IllegalStateException(context.getString(R.string.error_image_read_failed))
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0) return 1

        // Giữ đúng cách sample cũ (chỉ giảm khi CẢ hai chiều còn >= max) để
        // hash của ảnh phone thông thường vẫn cùng basis với hash đã lưu.
        var inSampleSize = 1
        if (width > maxDimension && height > maxDimension) {
            while (width / (inSampleSize * 2) >= maxDimension &&
                height / (inSampleSize * 2) >= maxDimension
            ) {
                inSampleSize *= 2
            }
        }

        // Chặn panorama/ảnh cực dài làm OOM: không decode quá ~4096px cạnh dài.
        val maxDecodeEdge = 4096
        var capSample = 1
        while (maxOf(width, height) / capSample > maxDecodeEdge) {
            capSample *= 2
        }
        return maxOf(inSampleSize, capSample)
    }

    private fun scaleDownToMax(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            throw IllegalStateException("Invalid bitmap dimensions: ${width}x$height")
        }
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = width.toFloat() / height.toFloat()
        val (newWidth, newHeight) = if (ratio > 1f) {
            maxDimension to (maxDimension / ratio).toInt()
        } else {
            (maxDimension * ratio).toInt() to maxDimension
        }
        return Bitmap.createScaledBitmap(
            bitmap,
            newWidth.coerceAtLeast(1),
            newHeight.coerceAtLeast(1),
            true,
        )
    }

    private fun compressBitmap(
        bitmap: Bitmap,
        maxDimension: Int,
        quality: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    ): ByteArray {
        val scaledBitmap = scaleDownToMax(bitmap, maxDimension)
        val outputStream = ByteArrayOutputStream()
        val compressed = scaledBitmap.compress(format, quality, outputStream)
        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }
        if (!compressed || outputStream.size() == 0) {
            throw IllegalStateException("Bitmap compression failed")
        }
        return outputStream.toByteArray()
    }
}
