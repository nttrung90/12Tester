package com.ntt.crosstest.localization

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.ntt.crosstest.R
import com.ntt.crosstest.ui.LocalizedAppContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ComposeLocaleRegressionTest {
    @Test
    fun reportDialogsKeepAppLanguageAcrossLanguageSwitches() = runBlocking {
        RuntimeEnvironment.setQualifiers("en")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val hostConfig = Configuration(activity.resources.configuration)
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        try {
            // A Compose Dialog uses LocalView's original Activity context and
            // installs new Android locals, even when the app overrides LocalContext.
            // Exercise that boundary, including a nested review/rejection dialog.
            for (language in listOf("vi", "en", "vi")) {
                val appConfig = Configuration(hostConfig).apply {
                    setLocale(Locale.forLanguageTag(language))
                }
                val context = activity.createConfigurationContext(appConfig)
                var dialogTitle = ""
                var pendingTab = ""
                var nestedReason = ""
                var dialogActivity: ComponentActivity? = null
                composition.setContent {
                    LocalizedAppContent(context, activity) {
                        CompositionLocalProvider(
                            LocalContext provides activity,
                            LocalConfiguration provides hostConfig,
                        ) {
                            dialogTitle = stringResource(R.string.oad_reports_dialog_title)
                            pendingTab = stringResource(R.string.oad_tab_pending_format, 3)
                            dialogActivity = LocalActivity.current as? ComponentActivity
                            CompositionLocalProvider(
                                LocalContext provides activity,
                                LocalConfiguration provides hostConfig,
                            ) {
                                nestedReason = stringResource(R.string.oad_reject_reason_1)
                            }
                        }
                    }
                }
                assertEquals(context.getString(R.string.oad_reports_dialog_title), dialogTitle)
                assertEquals(context.getString(R.string.oad_tab_pending_format, 3), pendingTab)
                assertEquals(context.getString(R.string.oad_reject_reason_1), nestedReason)
                assertEquals(activity, dialogActivity)
                if (language == "vi") assertEquals("Duyệt báo cáo kiểm thử", dialogTitle)
            }
        } finally {
            composition.dispose()
            recomposer.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun vietnameseUiOnEnglishDeviceUsesVietnameseResources() = runBlocking {
        RuntimeEnvironment.setQualifiers("en")
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("vi"))
        }
        val localizedContext = app.createConfigurationContext(config)
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        var title = ""
        var button = ""
        try {
            composition.setContent {
                CompositionLocalProvider(
                    LocalContext provides localizedContext,
                    LocalConfiguration provides config,
                ) {
                    title = stringResource(R.string.tab_profile)
                    button = LocalResources.current.getString(R.string.cancel)
                }
            }
            assertEquals("Hồ sơ", title)
            assertEquals("Hủy", button)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }
}
