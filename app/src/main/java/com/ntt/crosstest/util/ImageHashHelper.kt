package com.ntt.crosstest.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility for perceptual image hashing (dHash 64-bit) and duplicate detection.
 * Fast, lightweight (~10ms per bitmap), robust against JPEG compression & minor scale changes.
 */
object ImageHashHelper {

    private val hashCache = ConcurrentHashMap<String, String>()

    enum class SimilarityLevel {
        DUPLICATE,        // >= 92% similarity (Hamming <= 5): Likely the exact same screenshot re-uploaded
        HIGH_SIMILARITY,  // 85%..91% similarity (Hamming 6..9): Same static screen, minor changes (clock/battery)
        MODERATE,         // 75%..84% similarity (Hamming 10..15): Same flow/screen with interaction (opened dialog/keyboard)
        DIFFERENT         // < 75% similarity (Hamming >= 16): Distinctly different screens/features
    }

    data class ComparisonResult(
        val similarity: Int,
        val hammingDistance: Int,
        val level: SimilarityLevel,
        val label: String,
        val isSuspectDuplicate: Boolean,
    )

    /**
     * Computes a 64-bit difference hash (dHash) from a Bitmap.
     * Scales image to 9x8 grayscale and compares horizontally adjacent pixels.
     * Returns a 16-character lowercase hex string (e.g., "3a9c72f10b5d84e2").
     */
    fun computeDHash(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L

        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = getGrayscale(scaled.getPixel(x, y))
                val right = getGrayscale(scaled.getPixel(x + 1, y))
                if (left > right) {
                    hash = hash or (1L shl (y * 8 + x))
                }
            }
        }

        if (scaled != bitmap) {
            scaled.recycle()
        }

        return java.lang.Long.toHexString(hash).padStart(16, '0')
    }

    private fun getGrayscale(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * Decodes and computes dHash from a content Uri (e.g., from photo picker).
     */
    suspend fun computeDHashFromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val uriStr = uri.toString()
        hashCache[uriStr]?.let { return@withContext it }

        try {
            // First check bounds
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            var inSampleSize = 1
            val maxDim = 256
            val (w, h) = options.outWidth to options.outHeight
            if (w > maxDim || h > maxDim) {
                val halfW = w / 2
                val halfH = h / 2
                while ((halfW / inSampleSize) >= maxDim && (halfH / inSampleSize) >= maxDim) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            val hash = computeDHash(bitmap)
            bitmap.recycle()
            hashCache[uriStr] = hash
            hash
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Downloads/fetches cached bitmap via Coil and computes dHash from image URL.
     */
    suspend fun computeDHashFromUrl(context: Context, imageUrl: String): String? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null
        hashCache[imageUrl]?.let { return@withContext it }

        try {
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false) // Software bitmap needed for pixel reading
                .build()

            val result = context.imageLoader.execute(request)
            if (result is SuccessResult) {
                val drawable = result.drawable
                if (drawable is android.graphics.drawable.BitmapDrawable) {
                    val hash = computeDHash(drawable.bitmap)
                    hashCache[imageUrl] = hash
                    return@withContext hash
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Calculates the Hamming distance between two 16-hex dHash strings.
     * Returns 0..64 (number of differing bits).
     */
    fun hammingDistance(hash1: String?, hash2: String?): Int {
        if (hash1.isNullOrBlank() || hash2.isNullOrBlank()) return 64
        val h1 = hash1.toULongOrNull(16)?.toLong() ?: return 64
        val h2 = hash2.toULongOrNull(16)?.toLong() ?: return 64
        return java.lang.Long.bitCount(h1 xor h2)
    }

    /**
     * Calculates percentage similarity between 0% and 100%.
     */
    fun similarityPercentage(hash1: String?, hash2: String?): Int {
        val dist = hammingDistance(hash1, hash2)
        val sim = ((64 - dist) * 100) / 64
        return sim.coerceIn(0, 100)
    }

    /**
     * Evaluates comparison result between two image hashes.
     */
    fun compare(hash1: String?, hash2: String?): ComparisonResult {
        val dist = hammingDistance(hash1, hash2)
        val sim = ((64 - dist) * 100) / 64
        val clampedSim = sim.coerceIn(0, 100)

        val level = when {
            dist <= 5 -> SimilarityLevel.DUPLICATE
            dist <= 9 -> SimilarityLevel.HIGH_SIMILARITY
            dist <= 15 -> SimilarityLevel.MODERATE
            else -> SimilarityLevel.DIFFERENT
        }

        val label = when (level) {
            SimilarityLevel.DUPLICATE -> "Trùng lặp cao ($clampedSim%)"
            SimilarityLevel.HIGH_SIMILARITY -> "Rất giống nhau ($clampedSim%)"
            SimilarityLevel.MODERATE -> "Tương đồng một phần ($clampedSim%)"
            SimilarityLevel.DIFFERENT -> "Khác biệt ($clampedSim%)"
        }

        return ComparisonResult(
            similarity = clampedSim,
            hammingDistance = dist,
            level = level,
            label = label,
            isSuspectDuplicate = level == SimilarityLevel.DUPLICATE || level == SimilarityLevel.HIGH_SIMILARITY
        )
    }
}
