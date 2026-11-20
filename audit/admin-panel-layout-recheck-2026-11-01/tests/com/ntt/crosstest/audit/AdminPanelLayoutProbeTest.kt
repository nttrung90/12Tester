package com.ntt.crosstest.audit

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ntt.crosstest.data.Profile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "vi-rVN-w320dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminPanelLayoutProbeTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private val adminUserCardMethod by lazy {
        Class.forName("com.ntt.crosstest.ui.screens.AdminUsersScreenKt")
            .declaredMethods.single { it.name == "AdminUserCard" }.apply { isAccessible = true }
    }

    data class TextNodeInfo(val text: String, val bounds: Rect, val isEllipsized: Boolean)

    @OptIn(InternalComposeApi::class)
    private fun measureCard(
        width: Int,
        fontScale: Float,
        user: Profile,
        isCurrentAdmin: Boolean,
    ): List<TextNodeInfo> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            val view = ComposeView(activity)
            activity.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MaterialTheme {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp)) {
                            val callback: () -> Unit = {}
                            adminUserCardMethod.invoke(
                                null,
                                user,
                                isCurrentAdmin,
                                callback,
                                callback,
                                callback,
                                currentComposer,
                                0
                            )
                        }
                    }
                }
            }
            repeat(3) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, width, 1000)
            }
            val nativeView = descendants(view).first { candidate ->
                candidate.javaClass.methods.any { it.name == "getSemanticsOwner" }
            }
            val owner = nativeView.javaClass.getMethod("getSemanticsOwner").invoke(nativeView) as SemanticsOwner
            return owner.getAllSemanticsNodes(false).mapNotNull { node ->
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: return@mapNotNull null
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                val ellipsized = layouts.any { result -> (0 until result.lineCount).any { result.isLineEllipsized(it) } }
                TextNodeInfo(text, node.boundsInRoot, ellipsized)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun inspectRealMemberCardAtNarrowWidthsAndLargeFonts() {
        val output = mutableListOf<String>()
        val testUser = Profile(id = "member", displayName = "Nguyễn Văn An", email = "an@example.com", createdAt = "2026-11-01")
        for (width in listOf(320, 360, 411)) for (fontScale in listOf(1f, 1.3f, 2f)) {
            val textNodes = measureCard(width, fontScale, testUser, isCurrentAdmin = false)
            assertTrue("No text was composed at width=$width, fontScale=$fontScale", textNodes.isNotEmpty())

            val creditNode = textNodes.firstOrNull { it.text == "Tặng / Trừ Credit" }
            assertNotNull("Credit button label 'Tặng / Trừ Credit' missing at width=$width, fontScale=$fontScale", creditNode)
            assertTrue(
                "Credit button label 'Tặng / Trừ Credit' has zero width at width=$width, fontScale=$fontScale (bounds=${creditNode!!.bounds})",
                creditNode.bounds.width > 0f && creditNode.bounds.height > 0f
            )
            assertFalse(
                "Credit button label 'Tặng / Trừ Credit' is ellipsized at width=$width, fontScale=$fontScale",
                creditNode.isEllipsized
            )

            val lockNode = textNodes.firstOrNull { it.text == "Khóa tài khoản" }
            assertNotNull("Lock button label 'Khóa tài khoản' missing at width=$width, fontScale=$fontScale", lockNode)
            assertTrue(
                "Lock button label 'Khóa tài khoản' has zero width at width=$width, fontScale=$fontScale (bounds=${lockNode!!.bounds})",
                lockNode.bounds.width > 0f && lockNode.bounds.height > 0f
            )
            assertFalse(
                "Lock button label 'Khóa tài khoản' is ellipsized at width=$width, fontScale=$fontScale",
                lockNode.isEllipsized
            )

            val serialized = textNodes.map { "${it.text}:bounds=${it.bounds};ellipsis=${it.isEllipsized}" }
            output += "width=$width;fontScale=$fontScale\n" + serialized.joinToString("\n")
        }
        File("F:/AI/12Tester/audit/admin-panel-layout-recheck-2026-11-01/member-layout.txt").writeText(output.joinToString("\n\n"))
    }

    @Test
    fun testLockedMemberUnlockActionNoEllipsisAcrossWidthsAndFontScales() {
        val lockedUser = Profile(id = "member_locked", displayName = "Trần Thị B", email = "b@example.com", isLocked = true, createdAt = "2026-11-01")
        for (width in listOf(320, 360, 411)) for (fontScale in listOf(1f, 1.3f, 2f)) {
            val textNodes = measureCard(width, fontScale, lockedUser, isCurrentAdmin = false)
            val creditNode = textNodes.firstOrNull { it.text == "Tặng / Trừ Credit" }
            assertNotNull("Credit label missing for locked user at width=$width, fontScale=$fontScale", creditNode)
            assertFalse("Credit label ellipsized for locked user at width=$width, fontScale=$fontScale", creditNode!!.isEllipsized)
            assertTrue("Credit label bounds empty for locked user at width=$width, fontScale=$fontScale", creditNode.bounds.width > 0f)

            val unlockNode = textNodes.firstOrNull { it.text == "Mở khóa" }
            assertNotNull("Unlock label missing at width=$width, fontScale=$fontScale", unlockNode)
            assertFalse("Unlock label ellipsized at width=$width, fontScale=$fontScale", unlockNode!!.isEllipsized)
            assertTrue("Unlock label bounds empty at width=$width, fontScale=$fontScale", unlockNode.bounds.width > 0f)
        }
    }

    @Test
    fun testCurrentAdminCardHasNoSelfLockAction() {
        val adminUser = Profile(id = "admin_self", displayName = "Admin User", email = "admin@example.com", role = "admin", createdAt = "2026-11-01")
        for (width in listOf(320, 360, 411)) for (fontScale in listOf(1f, 1.3f, 2f)) {
            val textNodes = measureCard(width, fontScale, adminUser, isCurrentAdmin = true)
            val creditNode = textNodes.firstOrNull { it.text == "Tặng / Trừ Credit" }
            assertNotNull("Credit label missing for admin at width=$width, fontScale=$fontScale", creditNode)
            assertFalse("Credit label ellipsized for admin at width=$width, fontScale=$fontScale", creditNode!!.isEllipsized)
            assertTrue("Credit label bounds empty for admin at width=$width, fontScale=$fontScale", creditNode.bounds.width > 0f)

            val lockNode = textNodes.firstOrNull { it.text == "Khóa tài khoản" || it.text == "Mở khóa" }
            assertNull("Admin should not have lock/unlock button for their own card at width=$width, fontScale=$fontScale", lockNode)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h1000dp-mdpi")
    fun testEnglishLocaleActionsNoEllipsisAcrossWidthsAndFontScales() {
        val testUser = Profile(id = "member_en", displayName = "John Doe", email = "john@example.com", createdAt = "2026-11-01")
        for (width in listOf(320, 360, 411)) for (fontScale in listOf(1f, 1.3f, 2f)) {
            val textNodes = measureCard(width, fontScale, testUser, isCurrentAdmin = false)
            val creditNode = textNodes.firstOrNull { it.text == "Grant / Deduct Credit" }
            assertNotNull("EN Credit label 'Grant / Deduct Credit' missing at width=$width, fontScale=$fontScale", creditNode)
            assertFalse("EN Credit label ellipsized at width=$width, fontScale=$fontScale", creditNode!!.isEllipsized)
            assertTrue("EN Credit label bounds empty at width=$width, fontScale=$fontScale", creditNode.bounds.width > 0f)

            val lockNode = textNodes.firstOrNull { it.text == "Lock Account" }
            assertNotNull("EN Lock label 'Lock Account' missing at width=$width, fontScale=$fontScale", lockNode)
            assertFalse("EN Lock label ellipsized at width=$width, fontScale=$fontScale", lockNode!!.isEllipsized)
            assertTrue("EN Lock label bounds empty at width=$width, fontScale=$fontScale", lockNode.bounds.width > 0f)
        }
    }

    @OptIn(InternalComposeApi::class)
    @Test
    fun testMemberListScrollabilityAtMax360dpWhenCardsAreTaller() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            val view = ComposeView(activity)
            activity.setContentView(view)
            val listState = LazyListState()
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2.0f)) {
                    MaterialTheme {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .padding(horizontal = 30.dp),
                        ) {
                            items(5) { idx ->
                                val callback: () -> Unit = {}
                                adminUserCardMethod.invoke(
                                    null,
                                    Profile(id = "user_$idx", displayName = "Member $idx", email = "user$idx@example.com", createdAt = "2026-11-01"),
                                    false,
                                    callback,
                                    callback,
                                    callback,
                                    currentComposer,
                                    0
                                )
                            }
                        }
                    }
                }
            }
            repeat(3) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
                )
                view.layout(0, 0, 360, view.measuredHeight)
            }
            assertTrue("LazyColumn measured height must be <= 360dp constraint but was ${view.measuredHeight}", view.measuredHeight <= 360)
            assertTrue("LazyColumn should be scrollable with multiple tall cards", listState.canScrollForward)
            runBlocking {
                listState.scrollToItem(3)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals("LazyColumn should have scrolled to item 3", 3, listState.firstVisibleItemIndex)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
