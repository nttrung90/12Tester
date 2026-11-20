package com.ntt.crosstest.audit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.ntt.crosstest.util.ImageHashHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Characterization tests: these reproduce false positives in the current algorithm. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageHashCollisionAuditTest {
    @Test fun differentSolidColorsAreReportedAs100Percent() {
        val first = Bitmap.createBitmap(90, 80, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(90, 80, Bitmap.Config.ARGB_8888)
        try {
            first.eraseColor(Color.WHITE)
            second.eraseColor(Color.BLUE)
            assertFalse(first.sameAs(second))
            val result = ImageHashHelper.compare(
                ImageHashHelper.computeDHash(first), ImageHashHelper.computeDHash(second),
                RuntimeEnvironment.getApplication()
            )
            // dHash bit similarity is mathematically 100% (reproducing perceptual collision)
            assertEquals(100, result.similarity)
            // But per the new contract: dHash NEVER produces an exact or blocking duplicate
            assertFalse(result.isBlockingDuplicate)
            assertFalse(result.isExactDuplicate)
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    @Test fun changedPixelsWithSameBrightnessOrderingStillScore100() {
        val first = Bitmap.createBitmap(9, 8, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(9, 8, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 8) for (x in 0 until 9) {
                val base = 200 - x * 20
                first.setPixel(x, y, Color.rgb(base, base, base))
                val changed = base + if (x == 4 && y == 4) 8 else 0
                second.setPixel(x, y, Color.rgb(changed, changed, changed))
            }
            assertFalse(first.sameAs(second))
            assertEquals(100, ImageHashHelper.similarityPercentage(
                ImageHashHelper.computeDHash(first), ImageHashHelper.computeDHash(second)
            ))
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    @Test fun score100IsHashEqualityRatherThanRounding() {
        assertEquals(100, ImageHashHelper.similarityPercentage("0000000000000000", "0000000000000000"))
        assertEquals(98, ImageHashHelper.similarityPercentage("0000000000000000", "0000000000000001"))
    }

    @Test fun invalidOrMissingHashReturnsUnknownAndMinusOne() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(-1, ImageHashHelper.similarityPercentage(null, "0000000000000000"))
        assertEquals(-1, ImageHashHelper.similarityPercentage("", "0000000000000000"))
        assertEquals(-1, ImageHashHelper.similarityPercentage("123", "0000000000000000"))
        assertEquals(-1, ImageHashHelper.similarityPercentage("zzzzzzzzzzzzzzzz", "0000000000000000"))

        val nullComp = ImageHashHelper.compare(null, "0000000000000000", app)
        assertEquals(ImageHashHelper.SimilarityLevel.UNKNOWN, nullComp.level)
        assertFalse(nullComp.isSuspectDuplicate)
        assertFalse(nullComp.isBlockingDuplicate)
        assertEquals(-1, nullComp.similarity)
    }
}
