package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.myapplication.ui.detail.StockDetailScreen
import com.example.myapplication.ui.detail.StockDetailViewModel
import com.example.myapplication.ui.markets.MarketsScreen
import com.example.myapplication.ui.markets.MarketsViewModel
import com.example.myapplication.ui.portfolio.PortfolioScreen
import com.example.myapplication.ui.portfolio.PortfolioViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object MarketsRoute
@Serializable object PortfolioRoute
@Serializable data class StockDetailRoute(val symbol: String)

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as TradingApplication).repository

        // Hold the price WebSocket open only while the app is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { repository.streamPrices() }
        }

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val destination = backStackEntry?.destination
                val showBottomBar = destination?.hasRoute<StockDetailRoute>() != true

                Scaffold(
                    // Exposes Compose test tags as resource ids so UiAutomator (benchmarks) can find them.
                    modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar {
                                listOf(
                                    Triple(MarketsRoute, "Markets", Icons.AutoMirrored.Filled.ShowChart),
                                    Triple(PortfolioRoute, "Portfolio", Icons.Default.AccountBalanceWallet),
                                ).forEach { (route, label, icon) ->
                                    NavigationBarItem(
                                        selected = destination?.hasRoute(route::class) == true,
                                        onClick = {
                                            navController.navigate(route) {
                                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(icon, null) },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                    },
                ) { innerPadding ->
                    NavHost(
                        navController,
                        startDestination = MarketsRoute,
                        modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                    ) {
                        composable<MarketsRoute> {
                            MarketsScreen(
                                viewModel = viewModel(factory = viewModelFactory { initializer { MarketsViewModel(repository) } }),
                                onStockClick = { navController.navigate(StockDetailRoute(it)) },
                            )
                        }
                        composable<PortfolioRoute> {
                            PortfolioScreen(
                                viewModel = viewModel(factory = viewModelFactory { initializer { PortfolioViewModel(repository) } }),
                                onHoldingClick = { navController.navigate(StockDetailRoute(it)) },
                            )
                        }
                        composable<StockDetailRoute> { entry ->
                            val symbol = entry.toRoute<StockDetailRoute>().symbol
                            StockDetailScreen(
                                viewModel = viewModel(factory = viewModelFactory { initializer { StockDetailViewModel(symbol, repository) } }),
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
