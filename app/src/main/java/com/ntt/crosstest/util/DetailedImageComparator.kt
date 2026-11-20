package com.ntt.crosstest.util

import android.content.Context
import android.graphics.Bitmap
import com.ntt.crosstest.R
import com.ntt.crosstest.data.TesterReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Thuật toán so sánh chi tiết ảnh trên background dispatcher khi mở xem đối chiếu.
 *
 * Tiêu chí kỹ thuật bắt buộc:
 * - Thuật toán: region_rgb_v1
 * - Giới hạn bộ nhớ: ảnh làm việc thu về tối đa WORKING_MAX_DIMENSION (240px), không recycle bitmap nguồn dùng chung.
 * - Giữ nguyên tỉ lệ khung hình (aspect ratio); không kéo giãn hai tỉ lệ khác nhau.
 * - Xử lý alpha: hòa sắc alpha với nền trắng đục để tránh nhiễu do kênh trong suốt.
 * - Chia ô (8x8 = 64 cells): đo chênh lệch màu RGB trung bình toàn ảnh kết hợp ô có chênh lệch lớn nhất (peak),
 *   giúp phát hiện thay đổi cục bộ (dialog, bàn phím, văn bản nhập mới) ngay cả khi trung bình toàn ảnh đổi ít.
 * - Bitmap cache không có bằng chứng pixel gốc: chỉ cảnh báo gần giống, không kết luận trùng tuyệt đối.
 * - Tuyệt đối không hard-block tester từ kết quả so sánh; chỉ cung cấp thông tin trực quan cho người duyệt.
 */
object DetailedImageComparator {

    const val ALGORITHM_VERSION = "region_rgb_v1"
    const val WORKING_MAX_DIMENSION = 240
    const val GRID_COLS = 8
    const val GRID_ROWS = 8

    const val ASPECT_RATIO_TOLERANCE = 0.05f

    // Ngưỡng chênh lệch màu chuẩn hoá (0.0..1.0)
    const val MEAN_DIFF_THRESHOLD_DIFFERENT = 0.07f
    const val PEAK_DIFF_THRESHOLD_DIFFERENT = 0.18f
    const val MEAN_DIFF_THRESHOLD_SIMILAR = 0.02f
    const val PEAK_DIFF_THRESHOLD_SIMILAR = 0.06f

    enum class DetailedVerdict {
        UNAVAILABLE,          // Không đủ dữ liệu / ảnh lỗi / đã hết hạn
        DIFFERENT,            // Phát hiện khác biệt rõ rệt (màu sắc hoặc vùng giao diện)
        DIFFERENT_ASPECT,     // Khác tỉ lệ màn hình / hướng xoay
        SUSPECT_SIMILAR,      // Bố cục và màu sắc gần giống, cần người duyệt kiểm tra
    }

    data class DetailedComparisonResult(
        val verdict: DetailedVerdict,
        val meanDifference: Float,
        val peakDifference: Float,
        val titleResId: Int,
        val descResId: Int,
        val isExact: Boolean = false,
        val isSuspect: Boolean = false,
        val algorithmVersion: String = ALGORITHM_VERSION,
    )

    /**
     * So sánh 2 bitmap có sẵn trong bộ nhớ.
     * Lưu ý: Toàn bộ bitmap từ cache/Coil có thể đã bị subsample/downsample,
     * do đó tuyệt đối không trả về kết luận EXACT; tương đồng cao nhất là SUSPECT_SIMILAR.
     *
     * @param first Bitmap thứ nhất (ảnh hôm nay/mới)
     * @param second Bitmap thứ hai (ảnh đối chiếu ngày trước)
     * @param isSameFileUrl Cờ đánh dấu nếu 2 ảnh trỏ tới cùng 1 file URL (chỉ là tham chiếu tài nguyên)
     */
    suspend fun compareBitmaps(
        first: Bitmap?,
        second: Bitmap?,
        isSameFileUrl: Boolean = false,
    ): DetailedComparisonResult = withContext(Dispatchers.Default) {
        if (first == null || second == null || first.isRecycled || second.isRecycled) {
            return@withContext DetailedComparisonResult(
                verdict = DetailedVerdict.UNAVAILABLE,
                meanDifference = -1f,
                peakDifference = -1f,
                titleResId = R.string.detailed_verdict_unavailable,
                descResId = R.string.img_hash_unavailable,
            )
        }

        coroutineContext.ensureActive()

        // 1. Kiểm tra tỉ lệ khung hình (Aspect Ratio)
        val aspect1 = first.width.toFloat() / first.height.toFloat()
        val aspect2 = second.width.toFloat() / second.height.toFloat()
        val maxAspect = maxOf(aspect1, aspect2)
        if (maxAspect > 0f && abs(aspect1 - aspect2) / maxAspect > ASPECT_RATIO_TOLERANCE) {
            return@withContext DetailedComparisonResult(
                verdict = DetailedVerdict.DIFFERENT_ASPECT,
                meanDifference = 1f,
                peakDifference = 1f,
                titleResId = R.string.detailed_verdict_different_aspect,
                descResId = R.string.img_difference_detected,
                isExact = false,
                isSuspect = false,
            )
        }

        // 2. Chuẩn hoá ảnh về kích thước làm việc giới hạn bộ nhớ (giữ nguyên aspect ratio)
        val maxDim = maxOf(first.width, first.height).coerceAtLeast(1)
        val scale = if (maxDim > WORKING_MAX_DIMENSION) {
            WORKING_MAX_DIMENSION.toFloat() / maxDim.toFloat()
        } else {
            1f
        }
        val targetW = (first.width * scale).toInt().coerceAtLeast(GRID_COLS)
        val targetH = (first.height * scale).toInt().coerceAtLeast(GRID_ROWS)

        val scaled1 = if (scale < 1f || first.width != targetW || first.height != targetH) {
            Bitmap.createScaledBitmap(first, targetW, targetH, true)
        } else {
            first
        }

        val scaled2 = try {
            if (scale < 1f || second.width != targetW || second.height != targetH) {
                Bitmap.createScaledBitmap(second, targetW, targetH, true)
            } else {
                second
            }
        } catch (e: Exception) {
            if (scaled1 != first) scaled1.recycle()
            throw e
        }

        try {
            coroutineContext.ensureActive()

            // 3. Tính toán ma trận chênh lệch màu và vùng (8x8 = 64 ô)
            // Tính toán mean(abs(pixelRGB1 - pixelRGB2)) trên từng ô để tránh triệt tiêu khi hoán đổi màu
            val cellW = targetW / GRID_COLS
            val cellH = targetH / GRID_ROWS

            var totalDiff = 0f
            var maxCellDiff = 0f
            var cellsCount = 0

            for (gy in 0 until GRID_ROWS) {
                for (gx in 0 until GRID_COLS) {
                    coroutineContext.ensureActive()
                    val startX = gx * cellW
                    val startY = gy * cellH
                    val endX = if (gx == GRID_COLS - 1) targetW else (gx + 1) * cellW
                    val endY = if (gy == GRID_ROWS - 1) targetH else (gy + 1) * cellH

                    var cellPixelDiffSum = 0.0
                    var pixelCount = 0L

                    for (y in startY until endY) {
                        for (x in startX until endX) {
                            val p1 = scaled1.getPixel(x, y)
                            val p2 = scaled2.getPixel(x, y)

                            // Blend alpha với nền trắng đục
                            val a1 = (p1 ushr 24) and 0xFF
                            val r1 = ((p1 ushr 16) and 0xFF) * a1 / 255 + (255 - a1)
                            val g1 = ((p1 ushr 8) and 0xFF) * a1 / 255 + (255 - a1)
                            val b1 = (p1 and 0xFF) * a1 / 255 + (255 - a1)

                            val a2 = (p2 ushr 24) and 0xFF
                            val r2 = ((p2 ushr 16) and 0xFF) * a2 / 255 + (255 - a2)
                            val g2 = ((p2 ushr 8) and 0xFF) * a2 / 255 + (255 - a2)
                            val b2 = (p2 and 0xFF) * a2 / 255 + (255 - a2)

                            val pixelDiff = (abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)).toDouble() / (3.0 * 255.0)
                            cellPixelDiffSum += pixelDiff
                            pixelCount++
                        }
                    }

                    if (pixelCount > 0) {
                        val cellDiff = (cellPixelDiffSum / pixelCount).toFloat()
                        totalDiff += cellDiff
                        if (cellDiff > maxCellDiff) {
                            maxCellDiff = cellDiff
                        }
                        cellsCount++
                    }
                }
            }

            val meanDiff = if (cellsCount > 0) totalDiff / cellsCount else 0f
            val isDiff = meanDiff >= MEAN_DIFF_THRESHOLD_DIFFERENT || maxCellDiff >= PEAK_DIFF_THRESHOLD_DIFFERENT
            val isSimilar = meanDiff <= MEAN_DIFF_THRESHOLD_SIMILAR && maxCellDiff <= PEAK_DIFF_THRESHOLD_SIMILAR

            val verdict = when {
                isDiff -> DetailedVerdict.DIFFERENT
                isSimilar -> DetailedVerdict.SUSPECT_SIMILAR
                else -> DetailedVerdict.DIFFERENT
            }

            DetailedComparisonResult(
                verdict = verdict,
                meanDifference = meanDiff,
                peakDifference = maxCellDiff,
                titleResId = if (verdict == DetailedVerdict.SUSPECT_SIMILAR) R.string.detailed_verdict_suspect_similar else R.string.detailed_verdict_different,
                descResId = if (verdict == DetailedVerdict.SUSPECT_SIMILAR) R.string.img_suspect_warning else R.string.img_difference_detected,
                isExact = false,
                isSuspect = verdict == DetailedVerdict.SUSPECT_SIMILAR,
            )
        } finally {
            if (scaled1 != first) scaled1.recycle()
            if (scaled2 != second) scaled2.recycle()
        }
    }
}
