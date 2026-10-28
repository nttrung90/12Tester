package com.ntt.crosstest.util

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ntt.crosstest.R
import com.ntt.crosstest.data.TesterReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility for perceptual image hashing (dHash 64-bit) and duplicate detection.
 * Fast, lightweight (~10ms per bitmap), robust against JPEG compression & minor scale changes.
 *
 * Lưu ý: mọi hash dùng để so sánh phải được tính trên cùng một "basis" — ảnh đã
 * nén & lưu trên Storage (xem FirebaseStorageHelper.prepareScreenshot), không
 * tính trên ảnh gốc trước khi nén.
 */
object ImageHashHelper {

    private const val HASH_CACHE_SIZE = 128

    private val hashLock = Any()
    // Lưu ý: key gắn kèm sessionGeneration để chặn race condition khi clear() giữa chừng
    private val hashCache = LruCache<String, String>(HASH_CACHE_SIZE)

    /** Xoá cache hash (gọi khi đăng xuất để không giữ dữ liệu phiên cũ). */
    fun clearCache() {
        synchronized(hashLock) {
            hashCache.evictAll()
        }
    }

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
    ) {
        /** Chỉ chặn nộp khi gần như trùng khít; HIGH_SIMILARITY chỉ cảnh báo. */
        val isBlockingDuplicate: Boolean get() = level == SimilarityLevel.DUPLICATE
    }

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
     * Downloads/fetches cached bitmap via Coil and computes dHash from image URL.
     */
    suspend fun computeDHashFromUrl(
        context: Context,
        imageUrl: String,
        report: TesterReport? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null
        if (report?.imageHash?.isNotBlank() == true) return@withContext report.imageHash
        if (report?.isScreenshotExpired == true || ScreenshotManager.isExpired(imageUrl, report?.createdAt, report?.mediaExpired)) {
            return@withContext null
        }

        val genAtStart = ScreenshotManager.sessionGeneration.get()
        val cacheKey = "$genAtStart:$imageUrl"

        synchronized(hashLock) {
            if (ScreenshotManager.sessionGeneration.get() == genAtStart) {
                hashCache.get(cacheKey)?.let { return@withContext it }
            }
        }

        try {
            val bitmap = ScreenshotManager.getOrFetchBitmap(
                context = context,
                url = imageUrl,
                purpose = "hash",
                report = report,
            )

            // Guard trước khi tính hash
            if (genAtStart != ScreenshotManager.sessionGeneration.get()) {
                return@withContext null
            }
            if (ScreenshotManager.isExpired(imageUrl, report?.createdAt, report?.mediaExpired)) {
                return@withContext null
            }
            if (bitmap == null || bitmap.isRecycled) {
                return@withContext null
            }

            val hash = computeDHash(bitmap)

            // Guard trước return & publication: kiểm tra generation và expiry nguyên tử với lock
            synchronized(hashLock) {
                if (genAtStart != ScreenshotManager.sessionGeneration.get()) {
                    return@withContext null
                }
                if (ScreenshotManager.isExpired(imageUrl, report?.createdAt, report?.mediaExpired)) {
                    return@withContext null
                }
                hashCache.put(cacheKey, hash)
                return@withContext hash
            }
        } catch (ce: CancellationException) {
            throw ce
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
    fun compare(hash1: String?, hash2: String?, context: Context): ComparisonResult {
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
            SimilarityLevel.DUPLICATE -> context.getString(R.string.similarity_duplicate_format, clampedSim)
            SimilarityLevel.HIGH_SIMILARITY -> context.getString(R.string.similarity_high_format, clampedSim)
            SimilarityLevel.MODERATE -> context.getString(R.string.similarity_moderate_format, clampedSim)
            SimilarityLevel.DIFFERENT -> context.getString(R.string.similarity_different_format, clampedSim)
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
