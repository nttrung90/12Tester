package com.ntt.crosstest.util

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NetworkRecoveryTest {
    @Test fun connectivityWaitsForCapabilitiesAndUnregistersWhenStopped() = runTest {
        val context: Application = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        val statuses = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            NetworkMonitor.observeNetwork(context).collect { statuses.add(it) }
        }
        testScheduler.runCurrent()
        val callback = shadow.networkCallbacks.single()
        val network = org.robolectric.shadows.ShadowNetwork.newInstance(101)
        assertEquals(listOf(false), statuses)
        callback.onAvailable(network)
        testScheduler.runCurrent()
        assertEquals(listOf(false), statuses)
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        callback.onCapabilitiesChanged(network, capabilities)
        callback.onCapabilitiesChanged(network, capabilities)
        testScheduler.runCurrent()
        assertEquals(listOf(false, true), statuses)
        callback.onLost(network)
        testScheduler.runCurrent()
        callback.onCapabilitiesChanged(org.robolectric.shadows.ShadowNetwork.newInstance(102), capabilities)
        testScheduler.runCurrent()
        assertEquals(listOf(false, true, false, true), statuses)
        job.cancelAndJoin()
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun stalledIconUploadTimesOutAndCancelsSdkTask() = runTest {
        var cancelled = 0
        try {
            awaitIconUpload(timeoutMs = 1_000, onCancel = { cancelled++ }) { awaitCancellation() }
            fail("Expected timeout")
        } catch (e: IOException) {
            assertEquals("icon_upload_timeout", e.message)
        }
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(1, cancelled)
    }

    @Test fun leavingIconUploadCancelsSdkTaskAndPreservesCancellation() = runTest {
        var cancelled = 0
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            awaitIconUpload(onCancel = { cancelled++ }) { awaitCancellation() }
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, cancelled)
    }

    @Test fun completedIconUploadDoesNotCancelOrWaitForTimeout() = runTest {
        var cancelled = 0
        awaitIconUpload(onCancel = { cancelled++ }) { }
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(0, cancelled)
    }
}
