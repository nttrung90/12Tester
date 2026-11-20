package com.ntt.crosstest.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailedImageComparatorTest {

    @Test
    fun whiteVsBlueReportsDifferentColor() = runBlocking {
        val white = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val blue = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            white.eraseColor(Color.WHITE)
            blue.eraseColor(Color.BLUE)

            val result = DetailedImageComparator.compareBitmaps(white, blue)
            assertEquals(DetailedImageComparator.DetailedVerdict.DIFFERENT, result.verdict)
            assertTrue("Mean diff should be large for white vs blue", result.meanDifference > 0.5f)
            assertFalse(result.isExact)
            assertFalse(result.isSuspect)
        } finally {
            white.recycle()
            blue.recycle()
        }
    }

    @Test
    fun exactSameBitmapReportsSuspectSimilarNeverExact() = runBlocking {
        val first = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 120) for (x in 0 until 120) {
                val color = Color.rgb((x * 2) % 255, (y * 2) % 255, ((x + y)) % 255)
                first.setPixel(x, y, color)
                second.setPixel(x, y, color)
            }

            val result = DetailedImageComparator.compareBitmaps(first, second)
            // Cached bitmaps could be subsampled/compressed, so never exact; highest verdict is SUSPECT_SIMILAR
            assertEquals(DetailedImageComparator.DetailedVerdict.SUSPECT_SIMILAR, result.verdict)
            assertFalse("Never return exact from cached bitmap comparison", result.isExact)
            assertTrue(result.isSuspect)
            assertEquals(0f, result.meanDifference, 0.001f)
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    @Test
    fun sameFileUrlReportsSuspectSimilarNeverExact() = runBlocking {
        val first = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        try {
            val result = DetailedImageComparator.compareBitmaps(first, second, isSameFileUrl = true)
            assertEquals(DetailedImageComparator.DetailedVerdict.SUSPECT_SIMILAR, result.verdict)
            assertFalse("Never return exact from cached bitmap comparison", result.isExact)
            assertTrue(result.isSuspect)
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    @Test
    fun swappedColorsWithinCellDetectedAsDifferent() = runBlocking {
        // Image A: left half is black, right half is white in each cell
        // Image B: left half is white, right half is black
        // Their mean cell RGB is identical (127.5), but pixel differences are 100%!
        val size = 80
        val first = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val isLeftHalfOfCell = (x % 10) < 5
                    if (isLeftHalfOfCell) {
                        first.setPixel(x, y, Color.BLACK)
                        second.setPixel(x, y, Color.WHITE)
                    } else {
                        first.setPixel(x, y, Color.WHITE)
                        second.setPixel(x, y, Color.BLACK)
                    }
                }
            }

            val result = DetailedImageComparator.compareBitmaps(first, second)
            // mean(abs(pixelRGB1 - pixelRGB2)) correctly detects full pixel difference
            assertTrue("Mean pixel diff should be high for swapped patterns", result.meanDifference > 0.8f)
            assertEquals(DetailedImageComparator.DetailedVerdict.DIFFERENT, result.verdict)
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    @Test
    fun differentAspectRatiosReportDifferentAspect() = runBlocking {
        val landscape = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val portrait = Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888)
        try {
            val result = DetailedImageComparator.compareBitmaps(landscape, portrait)
            assertEquals(DetailedImageComparator.DetailedVerdict.DIFFERENT_ASPECT, result.verdict)
            assertFalse(result.isExact)
        } finally {
            landscape.recycle()
            portrait.recycle()
        }
    }

    @Test
    fun localizedChangeDetectedByPeakDiff() = runBlocking {
        // Base image: dark grey 160x160
        val first = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        try {
            first.eraseColor(Color.DKGRAY)
            second.eraseColor(Color.DKGRAY)

            // Modify a 20x20 cell in the center of 'second' to bright white
            for (y in 70 until 90) {
                for (x in 70 until 90) {
                    second.setPixel(x, y, Color.WHITE)
                }
            }

            val result = DetailedImageComparator.compareBitmaps(first, second)
            // Even if global mean difference is moderate, peak difference detects local UI change
            assertTrue(result.peakDifference > 0.15f)
            assertEquals(DetailedImageComparator.DetailedVerdict.DIFFERENT, result.verdict)
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    @Test
    fun nullOrRecycledBitmapsReturnUnavailable() = runBlocking {
        val resultNull = DetailedImageComparator.compareBitmaps(null, null)
        assertEquals(DetailedImageComparator.DetailedVerdict.UNAVAILABLE, resultNull.verdict)

        val recycled = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        recycled.recycle()
        val resultRecycled = DetailedImageComparator.compareBitmaps(recycled, recycled)
        assertEquals(DetailedImageComparator.DetailedVerdict.UNAVAILABLE, resultRecycled.verdict)
    }
}
