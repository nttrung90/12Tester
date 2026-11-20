package com.ntt.crosstest.util

import android.app.Application
import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EgressFinalPolicyAuditTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val url = "https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2Fcampaign%2Ftester%2Ffixture.jpg?alt=media&token=fixture"
    private fun bytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it); bitmap.recycle() }.toByteArray()
    }
    @After fun cleanup() {
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
        PrivateScreenshotDiskCache.clearAll(context)
        IconRoutingHelper.isCdnRoutingEnabled = false
        IconRoutingHelper.cdnHost = ""
        IconRoutingHelper.resetFallbackTracking()
    }
    @Test fun malformedOrConflictingMaxAgeCannotBecomeFreshThirtyMinuteCache() {
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = true
        for (header in listOf("private, max-age=not-a-number", "private, max-age=60oops", "private, max-age=60, max-age=0")) {
            assertFalse("Invalid freshness must not be upgraded to fresh local cache: $header",
                PrivateScreenshotDiskCache.put(context, "policy-audit", 0, ScreenshotVariant.FULL, url, bytes(), header, Instant.now().toString(), false))
        }
    }
    @Test fun malformedAgeCannotBeTreatedAsAJustGeneratedResponse() {
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = true
        for (age in listOf("not-a-number", "-5", "999999999999999999999999999999")) {
            assertFalse("Unknown response age must fail closed: $age",
                PrivateScreenshotDiskCache.put(context, "policy-audit", 0, ScreenshotVariant.FULL, url, bytes(), "private, max-age=120", Instant.now().toString(), false, ageHeader = age))
        }
    }
    @Test fun cdnRoutingOnlyRewritesIconsFromTheConfiguredStorageBucket() {
        IconRoutingHelper.isCdnRoutingEnabled = true
        IconRoutingHelper.cdnHost = "cdn.fixture.invalid"
        val own = "https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/app_icons%2Ffixture.webp?alt=media&token=fixture"
        assertTrue(IconRoutingHelper.resolveIconUrl(own).startsWith("https://cdn.fixture.invalid/app_icons/"))
        for (foreign in listOf(
            own.replace("/b/cross-testers.firebasestorage.app/", "/b/untrusted-project/"),
            "https://firebasestorage.googleapis.com/v0/b/untrusted-project/o/app_icons%2Fb%2Fcross-testers.firebasestorage.app%2Ffixture.webp?alt=media",
            "https://untrusted-project.firebasestorage.app/o/app_icons%2Ffixture.webp?alt=media&token=fixture"
        )) {
            assertFalse("Host alone does not prove bucket ownership", IconRoutingHelper.isPublicIconUrl(foreign))
            assertEquals("Foreign icons must keep their origin URL", foreign, IconRoutingHelper.resolveIconUrl(foreign))
        }
    }
}
