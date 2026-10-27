package com.ntt.crosstest.data

import com.google.firebase.storage.StorageException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReportSubmitPolicyTest {

    @Test
    fun cancellationException_isNeverRetryable() {
        val error = CancellationException("Job was cancelled")
        assertFalse(ReportSubmitPolicy.isRetryable(error))
    }

    @Test
    fun permanentLocalErrors_areNotRetryable() {
        assertFalse(ReportSubmitPolicy.isRetryable(Exception("image_read_failed")))
        assertFalse(ReportSubmitPolicy.isRetryable(Exception("bitmap compression failed")))
        assertFalse(ReportSubmitPolicy.isRetryable(Exception("screenshot_file_missing")))
        assertFalse(ReportSubmitPolicy.isRetryable(Exception("file not found: /path/to/img")))
    }

    @Test
    fun permanentServerErrors_areNotRetryable() {
        assertFalse(ReportSubmitPolicy.isRetryable(Exception("same_screenshot_rejected")))
        assertFalse(ReportSubmitPolicy.isRetryable(Exception("permission_denied")))
        assertFalse(ReportSubmitPolicy.isRetryable(Exception("account_locked")))
        assertFalse(ReportSubmitPolicy.isRetryable(Exception("cannot_test_own_app")))
        assertFalse(ReportSubmitPolicy.isRetryable(Exception("invalid_image_hash")))
    }

    @Test
    fun storageException_unauthorizedOrUnauthenticated_isNotRetryable() {
        val unauthorized = StorageException.fromExceptionAndHttpCode(null, 403)!!
        assertFalse(ReportSubmitPolicy.isRetryable(unauthorized))

        val unauthenticated = StorageException.fromExceptionAndHttpCode(null, 401)!!
        assertFalse(ReportSubmitPolicy.isRetryable(unauthenticated))
    }

    @Test
    fun storageException_retryLimitExceeded_isRetryable() {
        val transient = StorageException.fromExceptionAndHttpCode(null, 503)!!
        assertTrue(ReportSubmitPolicy.isRetryable(transient))
    }

    @Test
    fun transientNetworkErrors_areRetryable() {
        val timeout = IOException("SocketTimeoutException: connect timed out")
        assertTrue(ReportSubmitPolicy.isRetryable(timeout))

        val unknownHost = IOException("UnknownHostException: Unable to resolve host")
        assertTrue(ReportSubmitPolicy.isRetryable(unknownHost))
    }
}
