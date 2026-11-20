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
        UNKNOWN,          // Thiếu / sai định dạng dHash, không đủ dữ liệu so sánh
        SIMILAR_LAYOUT,   // Bố cục gần giống (Hamming <= 9): cần đối chiếu ảnh trực tiếp
        MODERATE,         // Bố cục tương đối khác (Hamming 10..15)
        DIFFERENT,        // Khác bố cục rõ rệt (Hamming >= 16)
        @Deprecated("Sử dụng SIMILAR_LAYOUT", ReplaceWith("SIMILAR_LAYOUT"))
        DUPLICATE,
        @Deprecated("Sử dụng SIMILAR_LAYOUT", ReplaceWith("SIMILAR_LAYOUT"))
        HIGH_SIMILARITY
    }

    data class ComparisonResult(
        val similarity: Int,
        val hammingDistance: Int,
        val level: SimilarityLevel,
        val label: String,
        val isSuspectDuplicate: Boolean,
        val isExactDuplicate: Boolean = false,
    ) {
        /**
         * dHash CHỈ là perceptual layout hash (64-bit so sánh sáng/tối 9x8),
         * KHÔNG BAO GIỜ là bằng chứng exact để hard-block.
         * Thuộc tính này giữ lại để tương thích callsite, luôn false khi không có bằng chứng exact.
         */
        val isBlockingDuplicate: Boolean get() = isExactDuplicate
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
     * Kiểm tra tính hợp lệ của chuỗi dHash (phải đúng 16 ký tự hexadecimal).
     */
    fun isValidDHash(hash: String?): Boolean {
        if (hash == null || hash.length != 16) return false
        return hash.all { c -> (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F') }
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
        if (report?.imageHash?.isNotBlank() == true && isValidDHash(report.imageHash)) {
            return@withContext report.imageHash
        }
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
     * Returns 0..64 (number of differing bits) or -1 if any hash is invalid.
     */
    fun hammingDistance(hash1: String?, hash2: String?): Int {
        if (!isValidDHash(hash1) || !isValidDHash(hash2)) return -1
        val h1 = hash1?.toULongOrNull(16)?.toLong() ?: return -1
        val h2 = hash2?.toULongOrNull(16)?.toLong() ?: return -1
        return java.lang.Long.bitCount(h1 xor h2)
    }

    /**
     * Calculates percentage similarity between 0% and 100%, or -1 if invalid.
     */
    fun similarityPercentage(hash1: String?, hash2: String?): Int {
        val dist = hammingDistance(hash1, hash2)
        if (dist < 0) return -1
        val sim = ((64 - dist) * 100) / 64
        return sim.coerceIn(0, 100)
    }

    /**
     * Evaluates comparison result between two image hashes.
     * Lưu ý: dHash CHỈ phản ánh tương đồng bố cục thô (9x8 grayscale), không đo tính năng mới
     * và không bao giờ được suy diễn thành bằng chứng trùng lặp tuyệt đối.
     */
    fun compare(hash1: String?, hash2: String?, context: Context): ComparisonResult {
        if (!isValidDHash(hash1) || !isValidDHash(hash2)) {
            return ComparisonResult(
                similarity = -1,
                hammingDistance = -1,
                level = SimilarityLevel.UNKNOWN,
                label = context.getString(R.string.similarity_unknown),
                isSuspectDuplicate = false,
                isExactDuplicate = false,
            )
        }

        val dist = hammingDistance(hash1, hash2)
        val sim = ((64 - dist) * 100) / 64
        val clampedSim = sim.coerceIn(0, 100)

        val level = when {
            dist <= 9 -> SimilarityLevel.SIMILAR_LAYOUT
            dist <= 15 -> SimilarityLevel.MODERATE
            else -> SimilarityLevel.DIFFERENT
        }

        val label = when (level) {
            SimilarityLevel.SIMILAR_LAYOUT -> context.getString(R.string.similarity_similar_layout)
            SimilarityLevel.MODERATE -> context.getString(R.string.similarity_moderate)
            SimilarityLevel.DIFFERENT -> context.getString(R.string.similarity_different)
            else -> context.getString(R.string.similarity_unknown)
        }

        return ComparisonResult(
            similarity = clampedSim,
            hammingDistance = dist,
            level = level,
            label = label,
            isSuspectDuplicate = (level == SimilarityLevel.SIMILAR_LAYOUT),
            isExactDuplicate = false,
        )
    }
}
