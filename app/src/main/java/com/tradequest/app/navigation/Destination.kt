package com.tradequest.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.tradequest.app.R

/**
 * Every top-level navigation target.
 *
 * The declaration order is the order shown in the navigation bar and rail.
 */
enum class Destination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val inPrimaryNavigation: Boolean,
) {
    HOME("home", R.string.nav_home, Icons.Filled.Home, inPrimaryNavigation = true),
    MARKET_LIBRARY("market_library", R.string.nav_market_library, Icons.AutoMirrored.Filled.List, inPrimaryNavigation = true),
    TRADING("trading", R.string.nav_trading, Icons.Filled.PlayArrow, inPrimaryNavigation = true),
    CHALLENGE("challenge", R.string.nav_challenge, Icons.Filled.Star, inPrimaryNavigation = true),
    JOURNAL("journal", R.string.nav_journal, Icons.Filled.Edit, inPrimaryNavigation = true),
    NEWS("news", R.string.nav_news, Icons.Filled.Info, inPrimaryNavigation = false),
    HISTORY("history", R.string.nav_history, Icons.Filled.DateRange, inPrimaryNavigation = false),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings, inPrimaryNavigation = true),
    ;

    companion object {
        val START: Destination = HOME

        /** Destinations rendered in the bottom bar or navigation rail. */
        val primary: List<Destination> = entries.filter { it.inPrimaryNavigation }

        fun fromRoute(route: String?): Destination? = entries.firstOrNull { it.route == route }
    }
}
