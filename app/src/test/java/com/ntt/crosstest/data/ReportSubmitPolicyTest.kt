package com.ntt.crosstest.data

import com.google.firebase.storage.StorageException
import com.google.android.gms.common.api.Status
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReportSubmitPolicyTest {
    @Test fun realRestExceptionsClassifyPermanentAndTransientHttpStatuses() = runBlocking {
        for (status in listOf(400, 401, 403, 404, 422, 408, 429, 500, 503)) {
            HttpClient(MockEngine { respond("{}", HttpStatusCode.fromValue(status)) }).use { client ->
                val error = RestException("fixture", null, client.get("https://unit.invalid/rpc"))
                org.junit.Assert.assertEquals(status, error.statusCode)
                org.junit.Assert.assertEquals(status in listOf(408, 429, 500, 503), ReportSubmitPolicy.isRetryable(error))
            }
        }
    }

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
        val transient = StorageException.fromErrorStatus(Status.RESULT_TIMEOUT)
        org.junit.Assert.assertEquals(StorageException.ERROR_RETRY_LIMIT_EXCEEDED, transient.errorCode)
        assertTrue(ReportSubmitPolicy.isRetryable(transient))
    }

    @Test fun checksumCanRetryUploadButNotUrlResolution() {
        val checksum = StorageException.fromExceptionAndHttpCode(null, 409)!!
        org.junit.Assert.assertEquals(StorageException.ERROR_INVALID_CHECKSUM, checksum.errorCode)
        assertTrue(ReportSubmitPolicy.isRetryable(checksum, ReportSubmitPolicy.Operation.UPLOAD))
        assertFalse(ReportSubmitPolicy.isRetryable(checksum, ReportSubmitPolicy.Operation.RESOLVE_URL))
    }

    @Test fun storageHttp503RemainsTransientBut404DoesNot() {
        val unavailable = StorageException.fromExceptionAndHttpCode(null, 503)!!
        org.junit.Assert.assertEquals(StorageException.ERROR_UNKNOWN, unavailable.errorCode)
        assertTrue(ReportSubmitPolicy.isRetryable(unavailable))
        assertFalse(ReportSubmitPolicy.isRetryable(StorageException.fromExceptionAndHttpCode(null, 404)!!))
    }

    @Test fun wrappedPermanentFailureAndCancellationAreRecognized() {
        assertFalse(ReportSubmitPolicy.isRetryable(IOException("wrapped", StorageException.fromExceptionAndHttpCode(null, 403))))
        assertFalse(ReportSubmitPolicy.isRetryable(IOException("wrapped", CancellationException("cancelled"))))
    }

    @Test
    fun transientNetworkErrors_areRetryable() {
        val timeout = IOException("SocketTimeoutException: connect timed out")
        assertTrue(ReportSubmitPolicy.isRetryable(timeout))

        val unknownHost = IOException("UnknownHostException: Unable to resolve host")
        assertTrue(ReportSubmitPolicy.isRetryable(unknownHost))
    }
}
