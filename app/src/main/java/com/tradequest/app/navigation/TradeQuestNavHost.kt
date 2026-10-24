package com.tradequest.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.tradequest.app.core.challenge.ChallengeLauncher
import com.tradequest.app.core.market.MarketRepository
import com.tradequest.app.feature.challenge.ChallengeDashboardScreen
import com.tradequest.app.feature.challenge.ChallengeOverviewSource
import com.tradequest.app.feature.history.HistoryScreen
import com.tradequest.app.feature.home.HomeScreen
import com.tradequest.app.feature.journal.JournalScreen
import com.tradequest.app.feature.market.MarketLibraryScreen
import com.tradequest.app.feature.news.NewsScheduleSource
import com.tradequest.app.feature.news.NewsScreen
import com.tradequest.app.feature.settings.SettingsScreen
import com.tradequest.app.feature.trading.TradingScreen
import com.tradequest.app.feature.trading.TradingSessionSource

@Composable
fun TradeQuestNavHost(
    navController: NavHostController,
    marketRepository: MarketRepository,
    challengeLauncher: ChallengeLauncher,
    sessionSource: TradingSessionSource,
    challengeSource: ChallengeOverviewSource,
    newsScheduleSource: NewsScheduleSource,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Destination.START.route,
        modifier = modifier,
    ) {
        composable(Destination.HOME.route) { HomeScreen() }
        composable(Destination.MARKET_LIBRARY.route) {
            MarketLibraryScreen(
                repository = marketRepository,
                launcher = challengeLauncher,
            )
        }
        composable(Destination.TRADING.route) {
            TradingScreen(
                repository = marketRepository,
                sessionSource = sessionSource,
                newsSource = newsScheduleSource,
            )
        }
        composable(Destination.CHALLENGE.route) {
            ChallengeDashboardScreen(source = challengeSource)
        }
        composable(Destination.JOURNAL.route) { JournalScreen() }
        composable(Destination.NEWS.route) { NewsScreen(source = newsScheduleSource) }
        composable(Destination.HISTORY.route) {
            HistoryScreen(source = challengeSource)
        }
        composable(Destination.SETTINGS.route) { SettingsScreen() }
    }
}
