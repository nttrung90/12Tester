package com.ntt.crosstest.ui.screens

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import com.ntt.crosstest.R
import com.ntt.crosstest.ui.LocalizedAppContent
import com.ntt.crosstest.ui.theme.TestCheoTheme
import com.ntt.crosstest.util.openExternalLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeSocialLinksTest {

    @Test
    fun socialActionsMatchLanguageSpecification() {
        val englishActions = socialActionsForLanguage("en")
        assertEquals(2, englishActions.size)
        assertEquals(R.drawable.ic_telegram, englishActions[0].iconRes)
        assertEquals(R.string.social_link_telegram, englishActions[0].labelRes)
        assertEquals("https://t.me/Crosstesters", englishActions[0].url)
        assertEquals(R.drawable.ic_whatsapp, englishActions[1].iconRes)
        assertEquals(R.string.social_link_whatsapp, englishActions[1].labelRes)
        assertEquals(
            "https://chat.whatsapp.com/DaEC045t8t38A7iBUX5tOZ?s=sh&p=a&mlu=4&ilr=4",
            englishActions[1].url,
        )

        val vietnameseActions = socialActionsForLanguage("vi")
        assertEquals(2, vietnameseActions.size)
        assertEquals(R.drawable.ic_telegram, vietnameseActions[0].iconRes)
        assertEquals(R.string.social_link_telegram, vietnameseActions[0].labelRes)
        assertEquals("https://t.me/Crosstesters", vietnameseActions[0].url)
        assertEquals(R.drawable.ic_zalo, vietnameseActions[1].iconRes)
        assertEquals(R.string.social_link_zalo, vietnameseActions[1].labelRes)
        assertEquals("https://zalo.me/g/cc0vbi7igt77w10yikch", vietnameseActions[1].url)

        assertTrue(socialActionsForLanguage("fr").isEmpty())
        assertTrue(socialActionsForLanguage(null).isEmpty())
    }

    @Test
    fun drawablesAndStringsLoadCleanly() {
        val context = RuntimeEnvironment.getApplication()
        assertNotNull(ContextCompat.getDrawable(context, R.drawable.ic_telegram))
        assertNotNull(ContextCompat.getDrawable(context, R.drawable.ic_whatsapp))
        assertNotNull(ContextCompat.getDrawable(context, R.drawable.ic_zalo))
        assertEquals("Telegram", context.getString(R.string.social_link_telegram))
        assertEquals("WhatsApp", context.getString(R.string.social_link_whatsapp))
        assertEquals("Zalo", context.getString(R.string.social_link_zalo))
    }

    @Test
    fun openExternalLinkLaunchesViewIntentAndHandlesErrorsGracefully() {
        val app = RuntimeEnvironment.getApplication()
        for (url in listOf(TELEGRAM_GROUP_URL, WHATSAPP_GROUP_URL, ZALO_GROUP_URL)) {
            openExternalLink(app, url)
            val intent = shadowOf(app).nextStartedActivity
            assertNotNull(intent)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
            assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        }

        val failingContext = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) {
                throw android.content.ActivityNotFoundException("No browser")
            }
        }
        openExternalLink(failingContext, ZALO_GROUP_URL)
        assertEquals(
            app.getString(R.string.oad_open_link_failed),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun homeFloatingActionsEnglishShowsAddThenTelegramThenWhatsApp() {
        verifyHomeFloatingActionsForLocale(
            languageTag = "en",
            expectedSocialLabelsAndUrls = listOf(
                R.string.social_link_telegram to TELEGRAM_GROUP_URL,
                R.string.social_link_whatsapp to WHATSAPP_GROUP_URL,
            ),
            unexpectedLabels = listOf(R.string.social_link_zalo),
            expectedTelegramClicks = 1,
            expectedExternalUrls = listOf(WHATSAPP_GROUP_URL),
        )
    }

    @Test
    fun homeFloatingActionsVietnameseShowsAddThenTelegramThenZalo() {
        verifyHomeFloatingActionsForLocale(
            languageTag = "vi",
            expectedSocialLabelsAndUrls = listOf(
                R.string.social_link_telegram to TELEGRAM_GROUP_URL,
                R.string.social_link_zalo to ZALO_GROUP_URL,
            ),
            unexpectedLabels = listOf(R.string.social_link_whatsapp),
            expectedTelegramClicks = 1,
            expectedExternalUrls = listOf(ZALO_GROUP_URL),
        )
    }

    @Test
    fun homeFloatingActionsEnglishUnreadBadgeShowsOnlyOnTelegram() {
        verifyUnreadBadge(languageTag = "en", unreadCount = 0, expectedBadgeText = null)
        verifyUnreadBadge(languageTag = "en", unreadCount = 5, expectedBadgeText = "5")
        verifyUnreadBadge(languageTag = "en", unreadCount = 150, expectedBadgeText = "99+")
    }

    @Test
    fun homeFloatingActionsVietnameseUnreadBadgeShowsOnlyOnTelegram() {
        verifyUnreadBadge(languageTag = "vi", unreadCount = 0, expectedBadgeText = null)
        verifyUnreadBadge(languageTag = "vi", unreadCount = 5, expectedBadgeText = "5")
        verifyUnreadBadge(languageTag = "vi", unreadCount = 150, expectedBadgeText = "99+")
    }

    private fun verifyUnreadBadge(
        languageTag: String,
        unreadCount: Int,
        expectedBadgeText: String?,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val localizedConfig = Configuration(activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        val localizedContext = activity.createConfigurationContext(localizedConfig)
        val composeView = ComposeView(activity).apply {
            setContent {
                LocalizedAppContent(localizedContext, activity) {
                    CompositionLocalProvider(LocalDensity provides Density(density = 2.0f, fontScale = 1.0f)) {
                        TestCheoTheme {
                            HomeFloatingActions(
                                onCreateClick = {},
                                onSocialClick = {},
                                onTelegramChatClick = {},
                                telegramUnreadCount = unreadCount,
                            )
                        }
                    }
                }
            }
        }
        try {
            activity.setContentView(composeView)
            val widthPx = (140 * 2.0f).toInt()
            val heightPx = (260 * 2.0f).toInt()
            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            composeView.layout(0, 0, widthPx, heightPx)
            ShadowLooper.idleMainLooper()

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            composeView.draw(Canvas(bitmap))

            val rootView = composeView.getChildAt(0) as ViewRootForTest
            val root = rootView.semanticsOwner.unmergedRootSemanticsNode
            val telegramLabel = localizedContext.getString(R.string.social_link_telegram)
            val telegramNode = findNodeByContentDescription(root, telegramLabel)
            assertNotNull(telegramNode)

            val otherLabelRes = if (languageTag == "vi") R.string.social_link_zalo else R.string.social_link_whatsapp
            val otherNode = findNodeByContentDescription(root, localizedContext.getString(otherLabelRes))
            assertNotNull(otherNode)

            val fiveNode = findNodeByText(root, "5")
            val ninetyNinePlusNode = findNodeByText(root, "99+")
            if (expectedBadgeText == null) {
                assertNull(fiveNode)
                assertNull(ninetyNinePlusNode)
            } else {
                val badgeNode = findNodeByText(root, expectedBadgeText)
                assertNotNull("Expected badge '$expectedBadgeText' for count=$unreadCount", badgeNode)
                // Badge must be anchored at top-right of Telegram button, well above Zalo/WhatsApp button
                assertTrue(
                    "Badge top (${badgeNode!!.boundsInRoot.top}) should be near Telegram top (${telegramNode!!.boundsInRoot.top}) and above other button (${otherNode!!.boundsInRoot.top})",
                    badgeNode.boundsInRoot.bottom <= otherNode.boundsInRoot.top,
                )
            }
        } finally {
            composeView.disposeComposition()
            controller.pause().stop().destroy()
        }
    }

    private fun verifyHomeFloatingActionsForLocale(
        languageTag: String,
        expectedSocialLabelsAndUrls: List<Pair<Int, String>>,
        unexpectedLabels: List<Int>,
        expectedTelegramClicks: Int,
        expectedExternalUrls: List<String>,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val localizedConfig = Configuration(activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        val localizedContext = activity.createConfigurationContext(localizedConfig)
        var createClicks = 0
        var telegramClicks = 0
        val clickedUrls = mutableListOf<String>()
        val composeView = ComposeView(activity).apply {
            setContent {
                LocalizedAppContent(localizedContext, activity) {
                    CompositionLocalProvider(LocalDensity provides Density(density = 2.0f, fontScale = 1.0f)) {
                        TestCheoTheme {
                            HomeFloatingActions(
                                onCreateClick = { createClicks++ },
                                onSocialClick = { url -> clickedUrls.add(url) },
                                onTelegramChatClick = { telegramClicks++ },
                            )
                        }
                    }
                }
            }
        }
        try {
            activity.setContentView(composeView)
            val widthPx = (120 * 2.0f).toInt()
            val heightPx = (240 * 2.0f).toInt()
            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            composeView.layout(0, 0, widthPx, heightPx)
            ShadowLooper.idleMainLooper()

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            composeView.draw(Canvas(bitmap))

            val rootView = composeView.getChildAt(0) as ViewRootForTest
            val root = rootView.semanticsOwner.unmergedRootSemanticsNode
            val addLabel = localizedContext.getString(R.string.create_campaign)
            val addNode = findNodeByContentDescription(root, addLabel)
            assertNotNull("Add FAB should exist", addNode)

            for (unexpectedRes in unexpectedLabels) {
                val label = localizedContext.getString(unexpectedRes)
                assertNull("Did not expect $label for locale $languageTag", findNodeByContentDescription(root, label))
            }

            var previousNode = addNode!!
            for ((labelRes, _) in expectedSocialLabelsAndUrls) {
                val label = localizedContext.getString(labelRes)
                val node = findNodeByContentDescription(root, label)
                assertNotNull("Expected $label for locale $languageTag", node)
                assertTrue(
                    "$label (${node!!.boundsInRoot.top}) must be below previous (${previousNode.boundsInRoot.bottom})",
                    node.boundsInRoot.top >= previousNode.boundsInRoot.bottom,
                )
                findClickableAncestorOrSelf(root, node)!!.config[SemanticsActions.OnClick].action?.invoke()
                previousNode = node
            }

            findClickableAncestorOrSelf(root, addNode)!!.config[SemanticsActions.OnClick].action?.invoke()
            ShadowLooper.idleMainLooper()

            assertEquals(1, createClicks)
            assertEquals(expectedTelegramClicks, telegramClicks)
            assertEquals(expectedExternalUrls, clickedUrls)
        } finally {
            composeView.disposeComposition()
            controller.pause().stop().destroy()
        }
    }

    private fun findNodeByText(node: SemanticsNode, text: String): SemanticsNode? {
        val textList = node.config.getOrNull(SemanticsProperties.Text)
        if (textList?.any { it.text == text } == true) return node
        for (child in node.children) {
            findNodeByText(child, text)?.let { return it }
        }
        return null
    }

    private fun findNodeByContentDescription(node: SemanticsNode, description: String): SemanticsNode? {
        val descList = node.config.getOrNull(SemanticsProperties.ContentDescription)
        if (descList?.contains(description) == true) return node
        for (child in node.children) {
            findNodeByContentDescription(child, description)?.let { return it }
        }
        return null
    }

    private fun findClickableAncestorOrSelf(root: SemanticsNode, target: SemanticsNode): SemanticsNode? {
        if (target.config.getOrNull(SemanticsActions.OnClick) != null) return target
        var parent: SemanticsNode? = target.parent
        while (parent != null) {
            if (parent.config.getOrNull(SemanticsActions.OnClick) != null) return parent
            parent = parent.parent
        }
        return root.takeIf { it.config.getOrNull(SemanticsActions.OnClick) != null }
    }
}
