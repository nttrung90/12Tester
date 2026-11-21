package com.ntt.crosstest.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ntt.crosstest.ui.theme.GreenSurface
import com.ntt.crosstest.ui.theme.GreenSurfaceDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class CampaignDetailReportButtonThemeTest {

    private fun contrastRatio(a: Color, b: Color): Float {
        val l1 = a.luminance()
        val l2 = b.luminance()
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun `isDarkSurface distinguishes dark and light theme surfaces`() {
        assertTrue(isDarkSurface(GreenSurfaceDark))
        assertFalse(isDarkSurface(GreenSurface))
    }

    @Test
    fun `light mode preserves existing navy background with readable white content`() {
        val lightColors = reportPrimaryActionButtonColors(isDark = false)

        assertEquals(Color(0xFF2C4A73), lightColors.containerColor)
        assertEquals(Color.White, lightColors.contentColor)
        assertTrue(
            "Light mode button text contrast must pass WCAG AA (>= 4.5)",
            contrastRatio(lightColors.containerColor, lightColors.contentColor) >= 4.5f,
        )
    }

    @Test
    fun `dark mode lightens submit report button background with high contrast`() {
        val lightColors = reportPrimaryActionButtonColors(isDark = false)
        val darkColors = reportPrimaryActionButtonColors(isDark = true)

        assertTrue(
            "Dark mode container must be significantly brighter than light mode navy",
            darkColors.containerColor.luminance() > lightColors.containerColor.luminance() + 0.35f,
        )
        assertTrue(
            "Dark mode container must stand out brightly on dark surface",
            contrastRatio(darkColors.containerColor, GreenSurfaceDark) >= 7.0f,
        )
        assertTrue(
            "Dark mode content on lighter button must pass WCAG AAA (>= 7.0)",
            contrastRatio(darkColors.containerColor, darkColors.contentColor) >= 7.0f,
        )
    }
}
