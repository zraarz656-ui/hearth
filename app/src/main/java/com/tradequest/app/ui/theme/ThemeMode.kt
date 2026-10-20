package com.tradequest.app.ui.theme

/**
 * Selectable application theme.
 *
 * AGENTS.md requires Dark, Light and True-black OLED themes. [COLOR_BLIND] is
 * retained as a distinct mode because chart readability depends on a
 * deuteranopia-safe bullish/bearish pair.
 */
enum class ThemeMode {
    DARK,
    LIGHT,
    OLED,
    COLOR_BLIND,
    ;

    /** True when the theme should use a dark surface palette. */
    val isDark: Boolean
        get() = this != LIGHT
}
