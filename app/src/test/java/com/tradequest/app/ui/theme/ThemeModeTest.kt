package com.tradequest.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `all four required themes exist`() {
        assertEquals(
            setOf("DARK", "LIGHT", "OLED", "COLOR_BLIND"),
            ThemeMode.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `only light theme is not dark`() {
        assertFalse(ThemeMode.LIGHT.isDark)
        assertTrue(ThemeMode.DARK.isDark)
        assertTrue(ThemeMode.OLED.isDark)
        assertTrue(ThemeMode.COLOR_BLIND.isDark)
    }

    @Test
    fun `oled surface is pure black`() {
        assertEquals(Color(0xFF000000), OledSurface)
    }

    @Test
    fun `oled scheme uses a black background and surface`() {
        val scheme = colorSchemeFor(ThemeMode.OLED, systemDark = false)
        assertEquals(Color(0xFF000000), scheme.background)
        assertEquals(Color(0xFF000000), scheme.surface)
    }

    @Test
    fun `dark and oled backgrounds differ`() {
        val dark = colorSchemeFor(ThemeMode.DARK, systemDark = true)
        val oled = colorSchemeFor(ThemeMode.OLED, systemDark = true)
        assertNotEquals(dark.background, oled.background)
    }

    @Test
    fun `color blind mode uses a distinct bullish bearish pair`() {
        val standard = chartColorsFor(ThemeMode.DARK)
        val colorBlind = chartColorsFor(ThemeMode.COLOR_BLIND)
        assertNotEquals(standard.bullish, colorBlind.bullish)
        assertNotEquals(standard.bearish, colorBlind.bearish)
        assertEquals(BullishBlue, colorBlind.bullish)
        assertEquals(BearishOrange, colorBlind.bearish)
    }

    @Test
    fun `bullish and bearish tokens are always distinct`() {
        ThemeMode.entries.forEach { mode ->
            val tokens = chartColorsFor(mode)
            assertNotEquals("bullish must differ from bearish for $mode", tokens.bullish, tokens.bearish)
        }
    }

    @Test
    fun `light mode follows system dark setting`() {
        assertEquals(
            colorSchemeFor(ThemeMode.DARK, systemDark = true),
            colorSchemeFor(ThemeMode.LIGHT, systemDark = true),
        )
        assertNotEquals(
            colorSchemeFor(ThemeMode.LIGHT, systemDark = false).background,
            colorSchemeFor(ThemeMode.LIGHT, systemDark = true).background,
        )
    }
}
