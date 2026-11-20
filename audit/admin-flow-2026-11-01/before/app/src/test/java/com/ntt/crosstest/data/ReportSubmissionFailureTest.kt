package com.ntt.crosstest.data

import android.app.Application
import com.ntt.crosstest.R
import com.ntt.crosstest.ui.viewmodels.friendlyError
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReportSubmissionFailureTest {
    private val localizer get() = Localizer(RuntimeEnvironment.getApplication())

    @Test fun configurationMessageFollowsActiveLanguage() {
        val app = RuntimeEnvironment.getApplication()
        try {
            for ((language, expected) in listOf("vi" to "Máy chủ", "en" to "The server")) {
                app.getSharedPreferences("testcheo_settings_sync", android.content.Context.MODE_PRIVATE)
                    .edit().putString("app_language", language).commit()
                assertTrue(friendlyError(ReportSubmissionFailure(ReportSubmissionFailure.SERVER_CONFIG, "rpc", "request"), localizer)
                    .startsWith(expected))
            }
        } finally {
            app.getSharedPreferences("testcheo_settings_sync", android.content.Context.MODE_PRIVATE)
                .edit().remove("app_language").commit()
        }
    }

    @Test fun cryptoConfigurationIsPermanentAndSupportsLegacyWorkOutput() {
        val message = "function digest(text, unknown) does not exist"
        assertEquals(ReportSubmissionFailure.SERVER_CONFIG, ReportSubmissionFailure.codeFor(Exception(message)))
        assertFalse(ReportSubmitPolicy.isRetryable(Exception(message)))
        assertEquals(localizer.get(R.string.error_report_server_configuration), friendlyError(Exception(message), localizer))
        val structured = ReportSubmissionFailure(ReportSubmissionFailure.SERVER_CONFIG, "rpc", "request")
        assertFalse(ReportSubmitPolicy.isRetryable(structured))
        assertEquals(localizer.get(R.string.error_report_server_configuration), friendlyError(structured, localizer))
    }

    @Test fun otherUndefinedFunctionsAreNotMisclassifiedAsCryptoConfiguration() {
        assertFalse(ReportSubmissionFailure.isCryptoConfigurationError("42883 function another_function() does not exist"))
        assertFalse(ReportSubmissionFailure.isCryptoConfigurationError("digest mismatch"))
    }

    @Test fun storageAndNetworkAndBusinessErrorsKeepDistinctMessages() {
        val storage = ReportSubmissionFailure("report_storage_-13021", "upload", "request")
        assertEquals(localizer.get(R.string.error_report_storage), friendlyError(storage, localizer))
        val network = ReportSubmissionFailure("report_rpc_timeout", "rpc", "request", "report_rpc_timeout")
        assertEquals(localizer.get(R.string.error_network), friendlyError(network, localizer))
        val business = ReportSubmissionFailure("report_rpc_P0001", "rpc", "request", "SAME_SCREENSHOT_REJECTED")
        assertEquals(localizer.get(R.string.error_same_screenshot), friendlyError(business, localizer))
    }
}
