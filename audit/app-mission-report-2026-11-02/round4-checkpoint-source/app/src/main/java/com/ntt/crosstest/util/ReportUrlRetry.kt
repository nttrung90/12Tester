package com.ntt.crosstest.util

import com.ntt.crosstest.data.ReportSubmitPolicy
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** A missing object preserves the upload error; other read failures explain recovery failure. */
internal fun reportRecoveryFailure(uploadError: Exception, urlError: Exception): Exception {
    val missing = generateSequence<Throwable>(urlError) { it.cause }.take(16)
        .filterIsInstance<StorageException>().any { it.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND }
    val primary = if (missing) uploadError else urlError
    val secondary = if (missing) urlError else uploadError
    if (primary !== secondary) primary.addSuppressed(secondary)
    return primary
}

/** Limits both app retries and the time spent waiting for SDK-internal retries. */
internal suspend fun resolveReportUrl(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 500L,
    requestTimeoutMs: Long = 8_000L,
    totalTimeoutMs: Long = 20_000L,
    getUrl: suspend () -> String,
): String {
    require(maxAttempts > 0 && initialDelayMs >= 0 && requestTimeoutMs > 0 && totalTimeoutMs > 0)
    return withTimeoutOrNull(totalTimeoutMs) {
        var delayMs = initialDelayMs
        repeat(maxAttempts) { attempt ->
            try {
                return@withTimeoutOrNull withTimeoutOrNull(requestTimeoutMs) { getUrl() }
                    ?: throw IOException("report_url_request_timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReportSubmitPolicy.cancellationCause(e)?.let { throw it }
                if (attempt == maxAttempts - 1 ||
                    !ReportSubmitPolicy.isRetryable(e, ReportSubmitPolicy.Operation.RESOLVE_URL)
                ) throw e
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(1_000L)
            }
        }
        error("unreachable")
    } ?: throw IOException("report_url_resolution_timeout")
}
