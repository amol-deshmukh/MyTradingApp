package com.example.myapplication.ui.news

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.NewsCategory
import com.example.myapplication.data.model.NewsItem
import com.example.myapplication.ui.components.ConnectionPill
import com.example.myapplication.ui.components.Gain
import com.example.myapplication.ui.components.Loss
import com.example.myapplication.ui.components.Skeleton
import com.example.myapplication.ui.preview.PreviewData
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun NewsScreen(viewModel: NewsViewModel, onSymbolClick: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NewsScreen(state, onFilterChange = viewModel::setFilter, onSymbolClick = onSymbolClick)
}

/** Stateless screen: renders [state] only, so it can be previewed and tested without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(
    state: NewsUiState,
    onFilterChange: (NewsCategory?) -> Unit,
    onSymbolClick: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("News") },
                actions = { ConnectionPill(state.connection) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            CategoryFilters(state.filter, onFilterChange)

            val listState = rememberLazyListState()
            // Items are prepended as they stream in. If the user is reading the top of the feed,
            // follow new items; if they've scrolled down, leave their position alone.
            val newestId = state.items.firstOrNull()?.id
            LaunchedEffect(newestId) {
                if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
            }

            LazyColumn(Modifier.fillMaxSize().testTag("news_list"), state = listState) {
                when {
                    state.items.isNotEmpty() -> {}
                    state.connection == ConnectionState.OFFLINE && state.filter == null ->
                        item { EmptyMessage("News is unavailable. Retrying…") }
                    state.filter != null && state.connection == ConnectionState.LIVE ->
                        item { EmptyMessage("No ${state.filter.label.lowercase()} news yet.") }
                    else -> items(6) { SkeletonRow() }
                }
                items(state.items, key = { it.id }) { item ->
                    NewsRow(
                        item,
                        onClick = item.symbol?.let { symbol -> { onSymbolClick(symbol) } },
                        modifier = Modifier.animateItem(),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun CategoryFilters(selected: NewsCategory?, onChange: (NewsCategory?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(selected = selected == null, onClick = { onChange(null) }, label = { Text("All") })
        }
        items(NewsCategory.entries) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onChange(category) },
                label = { Text(category.label) },
            )
        }
    }
}

@Composable
private fun NewsRow(item: NewsItem, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("news_row")
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            item.symbol?.let { SymbolChip(it, item.sentiment) }
            if (item.symbol != null) Spacer(Modifier.width(8.dp))
            Text(
                "${item.category.label} · ${timeFormat.format(Date(item.ts))}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(item.headline, style = MaterialTheme.typography.titleSmall)
        if (item.summary.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SymbolChip(symbol: String, sentiment: Int) {
    val color = when {
        sentiment > 0 -> Gain
        sentiment < 0 -> Loss
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        Text(
            symbol,
            color = if (sentiment == 0) MaterialTheme.colorScheme.onSurface else color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SkeletonRow() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Skeleton(Modifier.size(width = 120.dp, height = 12.dp))
        Spacer(Modifier.height(8.dp))
        Skeleton(Modifier.fillMaxWidth().height(16.dp))
        Spacer(Modifier.height(6.dp))
        Skeleton(Modifier.size(width = 220.dp, height = 12.dp))
    }
}

private val NewsCategory.label: String
    get() = when (this) {
        NewsCategory.MARKET -> "Market"
        NewsCategory.EARNINGS -> "Earnings"
        NewsCategory.ANALYST -> "Analyst"
        NewsCategory.PRICE_ALERT -> "Price alert"
    }

@Preview(showBackground = true)
@Composable
private fun NewsScreenPreview() {
    MyApplicationTheme(dynamicColor = false) {
        NewsScreen(
            NewsUiState(items = PreviewData.news, connection = ConnectionState.LIVE),
            onFilterChange = {}, onSymbolClick = {},
        )
    }
}

@Preview(showBackground = true, name = "Connecting")
@Composable
private fun NewsScreenConnectingPreview() {
    MyApplicationTheme(dynamicColor = false) {
        NewsScreen(NewsUiState(connection = ConnectionState.CONNECTING), onFilterChange = {}, onSymbolClick = {})
    }
}

@Preview(showBackground = true, name = "Filtered")
@Composable
private fun NewsScreenFilteredPreview() {
    MyApplicationTheme(dynamicColor = false) {
        NewsScreen(
            NewsUiState(
                items = PreviewData.news.filter { it.category == NewsCategory.ANALYST },
                filter = NewsCategory.ANALYST,
                connection = ConnectionState.RECONNECTING,
            ),
            onFilterChange = {}, onSymbolClick = {},
        )
    }
}
