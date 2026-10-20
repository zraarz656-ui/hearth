package com.tradequest.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.tradequest.app.feature.challenge.ChallengeDashboardScreen
import com.tradequest.app.feature.history.HistoryScreen
import com.tradequest.app.feature.home.HomeScreen
import com.tradequest.app.feature.journal.JournalScreen
import com.tradequest.app.feature.market.MarketLibraryScreen
import com.tradequest.app.feature.news.NewsScreen
import com.tradequest.app.feature.settings.SettingsScreen
import com.tradequest.app.feature.trading.TradingScreen

@Composable
fun TradeQuestNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Destination.START.route,
        modifier = modifier,
    ) {
        composable(Destination.HOME.route) { HomeScreen() }
        composable(Destination.MARKET_LIBRARY.route) { MarketLibraryScreen() }
        composable(Destination.TRADING.route) { TradingScreen() }
        composable(Destination.CHALLENGE.route) { ChallengeDashboardScreen() }
        composable(Destination.JOURNAL.route) { JournalScreen() }
        composable(Destination.NEWS.route) { NewsScreen() }
        composable(Destination.HISTORY.route) { HistoryScreen() }
        composable(Destination.SETTINGS.route) { SettingsScreen() }
    }
}
