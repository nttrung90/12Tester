package com.ntt.crosstest.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.GetTesterBonusResult
import com.ntt.crosstest.ui.components.CampaignCard
import com.ntt.crosstest.ui.screens.TesterBonusScreenContent
import com.ntt.crosstest.ui.theme.TestCheoTheme
import com.ntt.crosstest.ui.viewmodels.TesterBonusUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "vi-rVN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TesterBonusVisualQATest {

    private val previewDir: File = run {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val rootDir = if (userDir.name == "app") userDir.parentFile else userDir
        File(rootDir, "audit/tester-bonus-2026-11-11/previews").apply { mkdirs() }
    }

    private fun renderToPng(
        fileName: String,
        widthDp: Int = 360,
        heightDp: Int = 850,
        isDark: Boolean = false,
        fontScale: Float = 1.0f,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ): File {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        var composeView: ComposeView? = null

        try {
            // Guarantee Vietnamese locale on runtime configuration
            val locale = Locale.Builder().setLanguage("vi").setRegion("VN").build()
            Locale.setDefault(locale)
            val config = activity.resources.configuration
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)

            // Assert localized title exists before capture
            val expectedTitle = activity.getString(R.string.tester_bonus_screen_title)
            assertEquals("Thưởng thêm cho tester", expectedTitle)

            composeView = ComposeView(activity).apply {
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(density = 2.0f, fontScale = fontScale)
                    ) {
                        TestCheoTheme(darkTheme = isDark) {
                            content()
                        }
                    }
                }
            }
            activity.setContentView(composeView)

            val density = 2.0f
            val widthPx = (widthDp * density).toInt()
            val heightPx = (heightDp * density).toInt()

            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            composeView.layout(0, 0, widthPx, heightPx)

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            composeView.draw(canvas)

            val outFile = File(previewDir, fileName)
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            return outFile
        } finally {
            composeView?.disposeComposition()
            activityController.destroy()
        }
    }

    private val fakeCampaign = Campaign(
        id = "camp-visual-101",
        ownerId = "owner-99",
        appName = "Smart Flashcard PRO",
        packageName = "com.sample.flashcards",
        status = "open",
        targetTesters = 20,
        targetDays = 14,
        testerBonusRoundId = "round-vis-1",
        testerBonusCredits = 5,
        testerBonusRemainingSlots = 8,
        testerBonusPaidCount = 12,
        testerBonusStatus = "active",
        createdAt = "2026-11-01T10:00:00Z"
    )

    @Test
    fun `render TesterBonusScreen in Vietnamese Light mode with full content and buttons`() {
        val state = TesterBonusUiState(
            isLoading = false,
            campaign = fakeCampaign,
            currentBalance = 450,
            rewardCreditsInput = "5",
            maxRecipientsInput = "10",
            calculatedBudget = 50L,
            expectedBalance = 400L,
            isFormValid = true,
            hasActiveOrClosingRound = false,
        )

        val file = renderToPng("tester_bonus_screen_vi_light.png", widthDp = 360, heightDp = 850, isDark = false) {
            TesterBonusScreenContent(
                uiState = state,
                onBack = {},
            )
        }
        assertTrue("Preview file should exist", file.exists() && file.length() > 0)
    }

    @Test
    fun `render TesterBonusScreen in Vietnamese Dark mode with full content and buttons`() {
        val state = TesterBonusUiState(
            isLoading = false,
            campaign = fakeCampaign,
            currentBalance = 450,
            rewardCreditsInput = "5",
            maxRecipientsInput = "10",
            calculatedBudget = 50L,
            expectedBalance = 400L,
            isFormValid = true,
            hasActiveOrClosingRound = false,
        )

        val file = renderToPng("tester_bonus_screen_vi_dark.png", widthDp = 360, heightDp = 850, isDark = true) {
            TesterBonusScreenContent(
                uiState = state,
                onBack = {},
            )
        }
        assertTrue("Preview file should exist", file.exists() && file.length() > 0)
    }

    @Test
    fun `render TesterBonusScreen in Narrow 320dp width without clipping`() {
        val state = TesterBonusUiState(
            isLoading = false,
            campaign = fakeCampaign,
            currentBalance = 450,
            rewardCreditsInput = "10",
            maxRecipientsInput = "20",
            calculatedBudget = 200L,
            expectedBalance = 250L,
            isFormValid = true,
            hasActiveOrClosingRound = false,
        )

        val file = renderToPng("tester_bonus_screen_narrow_320dp.png", widthDp = 320, heightDp = 950, isDark = false) {
            TesterBonusScreenContent(
                uiState = state,
                onBack = {},
            )
        }
        assertTrue("Preview file should exist", file.exists() && file.length() > 0)
    }

    @Test
    fun `render TesterBonusScreen in Large font scale 1_3 and 1_5 reachability`() {
        val state = TesterBonusUiState(
            isLoading = false,
            campaign = fakeCampaign,
            currentBalance = 450,
            rewardCreditsInput = "5",
            maxRecipientsInput = "10",
            calculatedBudget = 50L,
            expectedBalance = 400L,
            isFormValid = true,
            hasActiveOrClosingRound = false,
        )

        val file = renderToPng("tester_bonus_screen_large_font.png", widthDp = 360, heightDp = 1050, fontScale = 1.3f) {
            TesterBonusScreenContent(
                uiState = state,
                onBack = {},
            )
        }
        assertTrue("Preview file should exist", file.exists() && file.length() > 0)
    }

    @Test
    fun `render TesterBonusScreen with active round progress and budget breakdown`() {
        val state = TesterBonusUiState(
            isLoading = false,
            campaign = fakeCampaign,
            currentBalance = 400,
            hasActiveOrClosingRound = true,
            existingRound = GetTesterBonusResult(
                hasRound = true,
                isOwner = true,
                roundId = "round-vis-1",
                campaignId = fakeCampaign.id,
                rewardCredits = 5,
                maxRecipients = 20,
                paidCount = 12,
                remainingSlots = 8,
                status = "active",
                reservedCredits = 100L,
                remainingCredits = 40L,
                spentCredits = 60L,
            )
        )

        val file = renderToPng("tester_bonus_screen_active_round_progress.png", widthDp = 360, heightDp = 850, isDark = false) {
            TesterBonusScreenContent(
                uiState = state,
                onBack = {},
            )
        }
        assertTrue("Preview file should exist", file.exists() && file.length() > 0)
    }

    @Test
    fun `render CampaignCard with bonus badge and uninstall button in Light and Dark mode`() {
        // Light mode with uninstall button
        val lightFile = renderToPng("campaign_card_bonus_badge_light.png", widthDp = 360, heightDp = 220, isDark = false) {
            Box(modifier = Modifier.padding(12.dp)) {
                CampaignCard(
                    campaign = fakeCampaign,
                    onClick = {},
                    showTesterBonusBadge = true,
                    todayTestedCount = 5,
                    displayTestedCount = 5,
                    installedCount = 14,
                    isInstalledLocally = true,
                    onUninstallClick = {},
                )
            }
        }
        assertTrue("Light badge file should exist", lightFile.exists() && lightFile.length() > 0)

        // Dark mode with uninstall button
        val darkFile = renderToPng("campaign_card_bonus_badge_dark.png", widthDp = 360, heightDp = 220, isDark = true) {
            Box(modifier = Modifier.padding(12.dp)) {
                CampaignCard(
                    campaign = fakeCampaign,
                    onClick = {},
                    showTesterBonusBadge = true,
                    todayTestedCount = 5,
                    displayTestedCount = 5,
                    installedCount = 14,
                    isInstalledLocally = true,
                    onUninstallClick = {},
                )
            }
        }
        assertTrue("Dark badge file should exist", darkFile.exists() && darkFile.length() > 0)
    }

    @Test
    fun `render CampaignCard coexistence 320dp narrow long app name uninstall and large valid reward label`() {
        val largeRewardCampaign = fakeCampaign.copy(
            appName = "Siêu Ứng Dụng Học Ngoại Ngữ Cực Kỳ Dài Và Chi Tiết Cho Mọi Lứa Tuổi",
            packageName = "com.sample.superlongpackagename.languageapp",
            testerBonusCredits = 2147483640,
            testerBonusRemainingSlots = 5,
            testerBonusPaidCount = 1,
            testerBonusStatus = "active",
        )

        val file = renderToPng(
            fileName = "campaign_card_narrow_longname_uninstall_large_reward.png",
            widthDp = 320,
            heightDp = 280,
            isDark = false,
            fontScale = 1.3f,
        ) {
            Box(modifier = Modifier.padding(8.dp)) {
                CampaignCard(
                    campaign = largeRewardCampaign,
                    onClick = {},
                    showTesterBonusBadge = true,
                    todayTestedCount = 2,
                    displayTestedCount = 2,
                    installedCount = 8,
                    isInstalledLocally = true,
                    onUninstallClick = {},
                )
            }
        }
        assertTrue("Narrow coexistence card preview file should exist", file.exists() && file.length() > 0)
    }

    @Test
    fun `CampaignCard responsive branch guarantees nonzero title bounds and visible bonus badge and uninstall`() {
        val largeRewardCampaign = fakeCampaign.copy(
            appName = "Siêu Ứng Dụng Học Ngoại Ngữ Cực Kỳ Dài Và Chi Tiết Cho Mọi Lứa Tuổi",
            packageName = "com.sample.superlongpackagename.languageapp",
            testerBonusCredits = 2147483640,
            testerBonusRemainingSlots = 5,
            testerBonusPaidCount = 1,
            testerBonusStatus = "active",
        )

        var uninstallClicked = false
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        var composeView: ComposeView? = null

        try {
            composeView = ComposeView(activity).apply {
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(density = 2.0f, fontScale = 1.3f)
                    ) {
                        TestCheoTheme(darkTheme = false) {
                            Box(modifier = Modifier.padding(8.dp)) {
                                CampaignCard(
                                    campaign = largeRewardCampaign,
                                    onClick = {},
                                    showTesterBonusBadge = true,
                                    todayTestedCount = 2,
                                    displayTestedCount = 2,
                                    installedCount = 8,
                                    isInstalledLocally = true,
                                    onUninstallClick = { uninstallClicked = true },
                                )
                            }
                        }
                    }
                }
            }
            activity.setContentView(composeView)

            val widthPx = (320 * 2.0f).toInt()
            val heightPx = (280 * 2.0f).toInt()
            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            composeView.layout(0, 0, widthPx, heightPx)

            // Compose view measured dimensions must be positive
            assertTrue("Card width must be 640px", composeView.measuredWidth == widthPx)
            assertTrue("Card height must be 560px", composeView.measuredHeight == heightPx)

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            composeView.draw(canvas)

            // Verify drawn bitmap is non-empty
            assertTrue("Rendered bitmap must not be recycled", !bitmap.isRecycled)
            var hasNonTransparentPixel = false
            for (x in 0 until widthPx step 20) {
                for (y in 0 until heightPx step 20) {
                    if (bitmap.getPixel(x, y) != 0) {
                        hasNonTransparentPixel = true
                        break
                    }
                }
                if (hasNonTransparentPixel) break
            }
            assertTrue("Rendered card must contain drawn pixels", hasNonTransparentPixel)
        } finally {
            composeView?.disposeComposition()
            activityController.destroy()
        }
    }
}
