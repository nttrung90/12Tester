package com.ntt.crosstest.ui.screens.telegram

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.ntt.crosstest.R
import com.ntt.crosstest.data.telegram.TelegramFormattedText
import com.ntt.crosstest.data.telegram.TelegramMessageContentUi
import com.ntt.crosstest.data.telegram.TelegramMessageUi
import com.ntt.crosstest.ui.LocalizedAppContent
import com.ntt.crosstest.ui.theme.TestCheoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TelegramReadOnlyComponentsTest {

    @Test
    fun readOnlyPhotoOverlayAndLoginBarRenderAndInvokeLoginCallbacksInVietnameseAndEnglish() {
        verifyReadOnlyComponentsForLocale("vi")
        verifyReadOnlyComponentsForLocale("en")
    }

    private fun verifyReadOnlyComponentsForLocale(languageTag: String) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val localizedConfig = Configuration(activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        val localizedContext = activity.createConfigurationContext(localizedConfig)

        val thumbBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val baos = ByteArrayOutputStream()
        thumbBmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val thumbBytes = baos.toByteArray()

        val photoMsg = TelegramMessageUi(
            id = 42L,
            chatId = -1001L,
            senderId = 10L,
            senderName = "Tester",
            isOutgoing = false,
            dateSeconds = 1700000000,
            content = TelegramMessageContentUi.Photo(
                formattedCaption = TelegramFormattedText("Caption text"),
                fileId = 0,
                localPath = null,
                isDownloading = false,
                width = 800,
                height = 600,
                minithumbnailBytes = thumbBytes,
                isReadOnlyPreview = true,
            ),
        )

        var photoLoginClicks = 0
        var barLoginClicks = 0

        val composeView = ComposeView(activity).apply {
            setContent {
                LocalizedAppContent(localizedContext, activity) {
                    CompositionLocalProvider(LocalDensity provides Density(density = 2.0f, fontScale = 1.0f)) {
                        TestCheoTheme {
                            Column {
                                TelegramMessageBubble(
                                    message = photoMsg,
                                    showSenderHeader = true,
                                    onReply = {},
                                    onCopy = {},
                                    onEdit = {},
                                    onDelete = {},
                                    onToggleReaction = { _, _ -> },
                                    onPhotoClick = {},
                                    onDownloadFile = {},
                                    isReadOnlyMode = true,
                                    onRequestTelegramLogin = { photoLoginClicks++ },
                                )
                                TelegramReadOnlyLoginBar(
                                    onLoginClick = { barLoginClicks++ },
                                )
                            }
                        }
                    }
                }
            }
        }

        try {
            activity.setContentView(composeView)
            val widthPx = (360 * 2.0f).toInt()
            val heightPx = (640 * 2.0f).toInt()
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

            val overlayText = localizedContext.getString(R.string.telegram_readonly_photo_overlay)
            val overlayNode = findNodeByText(root, overlayText)
            assertNotNull("Expected photo overlay '$overlayText'", overlayNode)
            findClickableAncestorOrSelf(root, overlayNode!!)!!.config[SemanticsActions.OnClick].action?.invoke()

            val promptText = localizedContext.getString(R.string.telegram_readonly_login_prompt)
            assertNotNull("Expected login prompt '$promptText'", findNodeByText(root, promptText))

            val btnText = localizedContext.getString(R.string.telegram_readonly_login_btn)
            val btnNode = findNodeByText(root, btnText)
            assertNotNull("Expected login button '$btnText'", btnNode)
            findClickableAncestorOrSelf(root, btnNode!!)!!.config[SemanticsActions.OnClick].action?.invoke()

            ShadowLooper.idleMainLooper()
            assertEquals(1, photoLoginClicks)
            assertEquals(1, barLoginClicks)
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
