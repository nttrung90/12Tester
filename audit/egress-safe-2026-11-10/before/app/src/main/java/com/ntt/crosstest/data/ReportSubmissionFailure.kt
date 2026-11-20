package com.ntt.crosstest.data

import com.google.firebase.storage.StorageException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException

/** WorkManager-safe diagnostics. Never log the server message or screenshot URL. */
class ReportSubmissionFailure(
    val code: String,
    val stage: String,
    val requestId: String,
    legacyMessage: String? = null,
    val originalCode: String? = null,
    val httpStatus: Int? = null,
) : Exception(legacyMessage ?: code) {
    companion object {
        const val SERVER_CONFIG = "report_server_configuration"

        fun isCryptoConfigurationError(message: String): Boolean {
            val text = message.lowercase()
            return "digest(" in text && ("42883" in text || "does not exist" in text)
        }

        fun causes(error: Throwable): List<Throwable> =
            generateSequence(error) { it.cause }.take(16).toList()

        fun originalCodeFor(error: Throwable): String? {
            val chain = causes(error)
            val pg = chain.filterIsInstance<PostgrestRestException>().firstOrNull()
            if (pg != null && !pg.code.isNullOrBlank()) return pg.code
            val storage = chain.filterIsInstance<StorageException>().firstOrNull()
            if (storage != null) return "storage_${storage.errorCode}"
            return null
        }

        fun httpStatusFor(error: Throwable): Int? {
            val chain = causes(error)
            return chain.filterIsInstance<PostgrestRestException>().firstOrNull()?.statusCode
                ?: chain.filterIsInstance<RestException>().firstOrNull()?.statusCode
                ?: chain.filterIsInstance<StorageException>().firstOrNull()?.httpResultCode
        }

        fun codeFor(error: Exception): String {
            val chain = causes(error)
            if (chain.any { isCryptoConfigurationError(it.message.orEmpty()) }) return SERVER_CONFIG
            val storage = chain.filterIsInstance<StorageException>().firstOrNull()
            if (storage != null) return "report_storage_${storage.errorCode}"
            val postgrest = chain.filterIsInstance<PostgrestRestException>().firstOrNull()
            if (postgrest != null && !postgrest.code.isNullOrBlank()) {
                return "report_rpc_${postgrest.code}"
            }
            val msg = error.message.orEmpty()
            if (msg.matches(Regex("[a-zA-Z_]{1,80}"))) return msg
            return "report_submit_failed"
        }
    }
}
