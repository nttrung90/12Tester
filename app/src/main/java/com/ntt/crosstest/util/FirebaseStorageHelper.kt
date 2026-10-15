package com.ntt.crosstest.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object FirebaseStorageHelper {

    /**
     * Compresses the selected screenshot to max 1080px and 80% JPEG quality,
     * then uploads to Firebase Storage (Google Cloud Console).
     * Returns the public HTTPS download URL.
     */
    suspend fun uploadReportScreenshot(
        context: Context,
        imageUri: Uri,
        campaignId: String,
        userId: String,
    ): String = withContext(Dispatchers.IO) {
        val bitmap = decodeSampledBitmapFromUri(context, imageUri, maxDimension = 1080)
        val compressedBytes = compressBitmap(bitmap, maxDimension = 1080, quality = 80)
        bitmap.recycle()

        val filename = "${userId}_${System.currentTimeMillis()}.jpg"
        val storageRef = Firebase.storage.reference.child("reports/$campaignId/$filename")

        // Upload bytes
        storageRef.putBytes(compressedBytes).await()

        // Get public download URL
        storageRef.downloadUrl.await().toString()
    }

    /**
     * Uploads an app icon Bitmap directly to Firebase Storage (public URL).
     */
    suspend fun uploadAppIconBitmap(
        bitmap: Bitmap,
        packageName: String,
    ): String = withContext(Dispatchers.IO) {
        val cleanPkg = packageName.ifBlank { "app" }.replace(Regex("[^a-zA-Z0-9_.]"), "_")
        val filename = "${cleanPkg}_${System.currentTimeMillis()}.png"
        val bytes = compressBitmap(bitmap, maxDimension = 256, quality = 90, format = Bitmap.CompressFormat.PNG)
        val storageRef = Firebase.storage.reference.child("app_icons/$filename")
        storageRef.putBytes(bytes).await()
        storageRef.downloadUrl.await().toString()
    }

    /**
     * Uploads an app icon from Uri directly to Firebase Storage.
     */
    suspend fun uploadAppIconUri(
        context: Context,
        imageUri: Uri,
        packageName: String,
    ): String = withContext(Dispatchers.IO) {
        val bitmap = decodeSampledBitmapFromUri(context, imageUri, maxDimension = 256)
        val url = uploadAppIconBitmap(bitmap, packageName)
        bitmap.recycle()
        url
    }

    private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, maxDimension: Int): Bitmap {
        // 1. First decode with inJustDecodeBounds=true to check dimensions
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        // 2. Calculate inSampleSize
        var inSampleSize = 1
        val (w, h) = options.outWidth to options.outHeight
        if (w > maxDimension || h > maxDimension) {
            val halfW = w / 2
            val halfH = h / 2
            while ((halfW / inSampleSize) >= maxDimension && (halfH / inSampleSize) >= maxDimension) {
                inSampleSize *= 2
            }
        }

        // 3. Decode bitmap with inSampleSize set and RGB_565 to conserve RAM
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: throw IllegalStateException("Không thể đọc file ảnh.")
    }

    private fun compressBitmap(
        bitmap: Bitmap,
        maxDimension: Int,
        quality: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    ): ByteArray {
        val width = bitmap.width
        val height = bitmap.height

        val scaledBitmap = if (width > maxDimension || height > maxDimension) {
            val ratio = width.toFloat() / height.toFloat()
            val (newWidth, newHeight) = if (ratio > 1f) {
                maxDimension to (maxDimension / ratio).toInt()
            } else {
                (maxDimension * ratio).toInt() to maxDimension
            }
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(format, quality, outputStream)
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        return outputStream.toByteArray()
    }
}
