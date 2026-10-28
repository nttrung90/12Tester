package com.ntt.crosstest.util

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EgressRound2OptInTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val url = "https://firebasestorage.googleapis.com/v0/b/fixture/o/reports%2Fcampaign%2Ftester%2Foptin.jpg?alt=media&token=fixture"

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
        ScreenshotManager.clear(context)
        ImageInstrumentation.isEnabled = false
        ImageInstrumentation.reset()
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        bitmap.recycle()
        ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Cache-Control", "private, no-store")
                .body(bytes.toResponseBody()).build()
        }.build()
    }

    @After fun teardown() {
        ScreenshotManager.customHttpClient = null
        ScreenshotManager.clear(context)
        ImageInstrumentation.isEnabled = false
        ImageInstrumentation.reset()
        Dispatchers.resetMain()
    }

    @Test fun optedOutDirectManagerMemoryHitDoesNotCollect() = runTest {
        assertNotNull(ScreenshotManager.getOrFetchBitmap(context, url))
        ImageInstrumentation.reset()
        assertNotNull(ScreenshotManager.getOrFetchBitmap(context, url))
        assertEquals("Direct manager must honor diagnostic opt-out on RAM hits", 0, ImageInstrumentation.snapshot().memoryHits)
    }

    @Test fun optedOutRuntimeLoaderMemoryHitDoesNotCollect() = runTest {
        val loader = ImageLoader.Builder(context)
            .components { add(ScreenshotCoilInterceptor(context)) }
            .eventListener(ImageInstrumentation.createEventListener()).build()
        try {
            val request = ImageRequest.Builder(context).data(url).build()
            assertTrue(loader.execute(request) is SuccessResult)
            ImageInstrumentation.reset()
            assertTrue(loader.execute(request) is SuccessResult)
            assertEquals("Runtime screenshot interceptor must honor diagnostic opt-out", 0, ImageInstrumentation.snapshot().memoryHits)
        } finally {
            loader.shutdown()
        }
    }
}
