package com.example.myapplication.ui.markets

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.StockQuote
import com.example.myapplication.ui.components.ConnectionPill
import com.example.myapplication.ui.components.ErrorBanner
import com.example.myapplication.ui.components.LivePrice
import com.example.myapplication.ui.components.Skeleton
import com.example.myapplication.ui.components.asSignedPercent
import com.example.myapplication.ui.components.changeColor
import com.example.myapplication.ui.preview.PreviewData
import com.example.myapplication.ui.theme.MyApplicationTheme

@Composable
fun MarketsScreen(viewModel: MarketsViewModel, onStockClick: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Marks "time to full display" for startup benchmarks: the list is usable once prices arrive.
    ReportDrawnWhen { state.stocks.isNotEmpty() }
    MarketsScreen(state, onRefresh = viewModel::refresh, onStockClick = onStockClick)
}

/** Stateless screen: renders [state] only, so it can be previewed and tested without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketsScreen(state: MarketsUiState, onRefresh: () -> Unit, onStockClick: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Markets") },
                actions = { ConnectionPill(state.connection) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing && state.stocks.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("markets_list")) {
                state.error?.let { msg ->
                    item { ErrorBanner(msg, onRetry = onRefresh) }
                }
                if (state.stocks.isEmpty() && state.error == null) {
                    items(8) { SkeletonRow() }
                }
                items(state.stocks, key = { it.symbol }) { quote ->
                    StockRow(quote, onClick = { onStockClick(quote.symbol) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun StockRow(quote: StockQuote, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("stock_row")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(quote.symbol, style = MaterialTheme.typography.titleMedium)
            Text(
                quote.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            LivePrice(quote.price, MaterialTheme.typography.titleMedium)
            Text(
                quote.changePercent.asSignedPercent(),
                color = changeColor(quote.change),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun SkeletonRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Skeleton(Modifier.size(width = 60.dp, height = 18.dp))
            Spacer(Modifier.height(6.dp))
            Skeleton(Modifier.size(width = 140.dp, height = 12.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Skeleton(Modifier.size(width = 80.dp, height = 18.dp))
            Spacer(Modifier.height(6.dp))
            Skeleton(Modifier.width(48.dp).height(12.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MarketsScreenPreview() {
    MyApplicationTheme(dynamicColor = false) {
        MarketsScreen(
            MarketsUiState(stocks = PreviewData.quotes, connection = ConnectionState.LIVE),
            onRefresh = {}, onStockClick = {},
        )
    }
}

@Preview(showBackground = true, name = "Loading")
@Composable
private fun MarketsScreenLoadingPreview() {
    MyApplicationTheme(dynamicColor = false) {
        MarketsScreen(
            MarketsUiState(isRefreshing = true, connection = ConnectionState.CONNECTING),
            onRefresh = {}, onStockClick = {},
        )
    }
}

@Preview(showBackground = true, name = "Error with stale data")
@Composable
private fun MarketsScreenErrorPreview() {
    MyApplicationTheme(dynamicColor = false) {
        MarketsScreen(
            MarketsUiState(
                stocks = PreviewData.quotes,
                error = "The server is taking too long to respond.",
                connection = ConnectionState.RECONNECTING,
            ),
            onRefresh = {}, onStockClick = {},
        )
    }
}
