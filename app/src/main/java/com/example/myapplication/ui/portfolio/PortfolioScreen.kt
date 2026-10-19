package com.example.myapplication.ui.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.Holding
import com.example.myapplication.ui.components.ConnectionPill
import com.example.myapplication.ui.components.ErrorBanner
import com.example.myapplication.ui.components.LivePrice
import com.example.myapplication.ui.components.Skeleton
import com.example.myapplication.ui.components.asMoney
import com.example.myapplication.ui.components.asSignedMoney
import com.example.myapplication.ui.components.asSignedPercent
import com.example.myapplication.ui.components.changeColor
import com.example.myapplication.ui.preview.PreviewData
import com.example.myapplication.ui.theme.MyApplicationTheme

@Composable
fun PortfolioScreen(viewModel: PortfolioViewModel, onHoldingClick: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PortfolioScreen(state, onRefresh = viewModel::refresh, onHoldingClick = onHoldingClick)
}

/** Stateless screen: renders [state] only, so it can be previewed and tested without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(state: PortfolioUiState, onRefresh: () -> Unit, onHoldingClick: (String) -> Unit) {

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Portfolio") }, actions = { ConnectionPill(state.connection) })
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing && state.loaded,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                state.error?.let { item { ErrorBanner(it, onRetry = onRefresh) } }
                item { SummaryCard(state) }
                if (state.loaded) {
                    item {
                        Text(
                            "Holdings",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    if (state.holdings.isEmpty()) {
                        item {
                            Text(
                                "No holdings yet. Buy a stock from Markets.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        }
                    }
                    items(state.holdings, key = { it.symbol }) { h ->
                        HoldingRow(h, onClick = { onHoldingClick(h.symbol) })
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                } else if (state.error == null) {
                    items(3) { Skeleton(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp, vertical = 6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: PortfolioUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Account value", style = MaterialTheme.typography.labelLarge)
            if (!state.loaded) {
                Skeleton(Modifier.fillMaxWidth(0.6f).height(40.dp).padding(vertical = 4.dp))
                return@Column
            }
            Text(state.accountValue.asMoney(), style = MaterialTheme.typography.headlineMedium)
            Text(
                "${state.totalReturn.asSignedMoney()} (${state.totalReturnPercent.asSignedPercent()}) all time",
                color = changeColor(state.totalReturn),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Metric("Invested", state.invested.asMoney())
                Metric("Market value", state.marketValue.asMoney())
                Metric("Cash", state.cash.asMoney())
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun HoldingRow(h: Holding, onClick: () -> Unit) {
    val value = h.lastPrice * h.quantity
    val pnl = (h.lastPrice - h.avgPrice) * h.quantity
    val pnlPct = if (h.avgPrice == 0.0) 0.0 else (h.lastPrice - h.avgPrice) / h.avgPrice * 100
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(h.symbol, style = MaterialTheme.typography.titleMedium)
            Text(
                "${h.quantity} shares · avg ${h.avgPrice.asMoney()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            LivePrice(value, MaterialTheme.typography.titleMedium)
            Text(
                "${pnl.asSignedMoney()} (${pnlPct.asSignedPercent()})",
                color = changeColor(pnl),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PortfolioScreenPreview() {
    MyApplicationTheme(dynamicColor = false) {
        PortfolioScreen(
            PortfolioUiState(loaded = true, cash = 25_000.0, holdings = PreviewData.holdings, connection = ConnectionState.LIVE),
            onRefresh = {}, onHoldingClick = {},
        )
    }
}

@Preview(showBackground = true, name = "Loading")
@Composable
private fun PortfolioScreenLoadingPreview() {
    MyApplicationTheme(dynamicColor = false) {
        PortfolioScreen(PortfolioUiState(isRefreshing = true), onRefresh = {}, onHoldingClick = {})
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun PortfolioScreenEmptyPreview() {
    MyApplicationTheme(dynamicColor = false) {
        PortfolioScreen(
            PortfolioUiState(loaded = true, cash = 25_000.0, connection = ConnectionState.LIVE),
            onRefresh = {}, onHoldingClick = {},
        )
    }
}
