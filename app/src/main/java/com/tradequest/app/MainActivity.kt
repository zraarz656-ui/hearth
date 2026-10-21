package com.tradequest.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tradequest.app.di.AppContainer
import com.tradequest.app.navigation.AdaptiveNavigationBar
import com.tradequest.app.navigation.Destination
import com.tradequest.app.navigation.TradeQuestNavHost
import com.tradequest.app.ui.theme.ThemeMode
import com.tradequest.app.ui.theme.TradeQuestTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TradeQuestApplication).container
        setContent { TradeQuestApp(container) }
    }
}

@Composable
private fun TradeQuestApp(container: AppContainer) {
    // Phase 0 keeps theme selection in memory. Persistence arrives with the
    // Settings table in Phase 2.
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.DARK) }

    TradeQuestTheme(mode = themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val current = Destination.fromRoute(backStackEntry?.destination?.route)
            ?: Destination.START

        Scaffold(
            bottomBar = {
                if (current.inPrimaryNavigation) {
                    AdaptiveNavigationBar(
                        current = current,
                        onSelect = { destination ->
                            if (destination != current) {
                                navController.navigate(destination.route) {
                                    popUpTo(Destination.START.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                    )
                }
            },
        ) { padding ->
            TradeQuestNavHost(
                navController = navController,
                marketRepository = container.marketRepository,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
