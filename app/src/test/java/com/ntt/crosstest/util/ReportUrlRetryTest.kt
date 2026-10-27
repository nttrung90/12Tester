package com.ntt.crosstest.util

import com.google.firebase.storage.StorageException
import com.ntt.crosstest.data.ReportSubmitPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReportUrlRetryTest {
    @Test fun existingObjectWriteDenialDoesNotHideTransientUrlFailure() {
        val denied = StorageException.fromExceptionAndHttpCode(null, 403)!!
        val unavailable = StorageException.fromExceptionAndHttpCode(null, 503)!!
        val selected = reportRecoveryFailure(denied, unavailable)
        assertSame(unavailable, selected)
        assertTrue(ReportSubmitPolicy.isRetryable(selected))
        assertSame(denied, selected.suppressed.single())
    }
    @Test fun missingRecoveryObjectPreservesChecksumUploadRetry() {
        val checksum = StorageException.fromExceptionAndHttpCode(null, 409)!!
        val missing = StorageException.fromExceptionAndHttpCode(null, 404)!!
        assertSame(checksum, reportRecoveryFailure(checksum, missing))
        assertTrue(ReportSubmitPolicy.isRetryable(checksum))
    }
    @Test fun permanentRecoveryFailureStopsTransientUploadRetry() {
        val upload = StorageException.fromExceptionAndHttpCode(null, 503)!!
        val denied = StorageException.fromExceptionAndHttpCode(null, 403)!!
        val selected = reportRecoveryFailure(upload, denied)
        assertSame(denied, selected)
        assertFalse(ReportSubmitPolicy.isRetryable(selected))
    }
    @Test fun permissionFailureDoesNotRetryLocally() = runTest {
        var calls = 0
        val error = StorageException.fromExceptionAndHttpCode(null, 403)!!
        try {
            resolveReportUrl { calls++; throw error }
            fail("expected permission failure")
        } catch (actual: StorageException) { assertSame(error, actual) }
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun unknownFailureDoesNotRetryLocally() = runTest {
        var calls = 0
        try { resolveReportUrl { calls++; error("bad configuration") }; fail() }
        catch (_: IllegalStateException) {}
        assertEquals(1, calls)
    }
    @Test fun transientFailureRecoversWithoutUnnecessaryDelay() = runTest {
        var calls = 0
        val url = resolveReportUrl { if (++calls == 1) throw IOException("timeout") else "url" }
        assertEquals("url", url)
        assertEquals(2, calls)
        assertEquals(500L, testScheduler.currentTime)
    }
    @Test fun exhaustedRetriesRespectTheRequestBudget() = runTest {
        var calls = 0
        try { resolveReportUrl { calls++; throw IOException("timeout") }; fail() }
        catch (_: IOException) {}
        assertEquals(3, calls)
        assertEquals(1_500L, testScheduler.currentTime)
    }
    @Test fun requestTimeoutRetriesAreBounded() = runTest {
        var calls = 0
        try {
            resolveReportUrl(initialDelayMs = 0, requestTimeoutMs = 100, totalTimeoutMs = 1_000) {
                calls++; awaitCancellation()
            }
            fail()
        } catch (_: IOException) {}
        assertEquals(3, calls)
        assertEquals(300L, testScheduler.currentTime)
    }
    @Test fun totalDeadlineAlsoBoundsSdkWaitingAndDelays() = runTest {
        var calls = 0
        try {
            resolveReportUrl(requestTimeoutMs = 200, totalTimeoutMs = 250) { calls++; awaitCancellation() }
            fail()
        } catch (e: IOException) { assertEquals("report_url_resolution_timeout", e.message) }
        assertEquals(1, calls)
        assertEquals(250L, testScheduler.currentTime)
        currentCoroutineContext().ensureActive()
    }
    @Test fun cancellationDuringBackoffStopsFurtherCalls() = runTest {
        var calls = 0
        val job = launch { resolveReportUrl { calls++; throw IOException("timeout") } }
        runCurrent()
        job.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(1, calls)
    }
    @Test fun outerTimeoutIsNeverConvertedToNetworkFailure() = runTest {
        try {
            withTimeout(50) { resolveReportUrl { awaitCancellation() } }
            fail()
        } catch (_: TimeoutCancellationException) {}
        assertEquals(50L, testScheduler.currentTime)
    }
    @Test fun wrappedCancellationIsRethrownWithoutRetry() = runTest {
        var calls = 0
        try {
            resolveReportUrl { calls++; throw IOException("wrapped", CancellationException("cancel")) }
            fail()
        } catch (_: CancellationException) {}
        assertEquals(1, calls)
    }
}
