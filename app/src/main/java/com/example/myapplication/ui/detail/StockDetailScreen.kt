package com.example.myapplication.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.data.model.ConnectionState
import com.example.myapplication.data.model.OrderSide
import com.example.myapplication.data.model.StockQuote
import com.example.myapplication.data.model.StockDetail
import com.example.myapplication.ui.components.ChangeChip
import com.example.myapplication.ui.components.ConnectionPill
import com.example.myapplication.ui.components.ErrorBanner
import com.example.myapplication.ui.components.Gain
import com.example.myapplication.ui.components.LivePrice
import com.example.myapplication.ui.components.Loss
import com.example.myapplication.ui.components.Skeleton
import com.example.myapplication.ui.components.Sparkline
import com.example.myapplication.ui.components.asCompact
import com.example.myapplication.ui.components.asMoney
import com.example.myapplication.ui.components.asSignedMoney
import com.example.myapplication.ui.components.changeColor
import com.example.myapplication.ui.preview.PreviewData
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.util.Locale

@Composable
fun StockDetailScreen(viewModel: StockDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StockDetailScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenOrder = viewModel::openOrder,
        onQuantity = viewModel::setQuantity,
        onConfirmOrder = viewModel::submitOrder,
        onDismissOrder = viewModel::dismissOrder,
        onMessageShown = viewModel::messageShown,
    )
}

/** Stateless screen: renders [state] only, so it can be previewed and tested without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    state: StockDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenOrder: (OrderSide) -> Unit,
    onQuantity: (Int) -> Unit,
    onConfirmOrder: () -> Unit,
    onDismissOrder: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.symbol) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = { ConnectionPill(state.connection) },
            )
        },
        bottomBar = {
            TradeBar(
                enabled = state.quote != null,
                canSell = (state.holding?.quantity ?: 0) > 0,
                onBuy = { onOpenOrder(OrderSide.BUY) },
                onSell = { onOpenOrder(OrderSide.SELL) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // Thin progress bar while revalidating, so cached content stays visible underneath.
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Spacer(Modifier.height(4.dp))

            state.error?.let { ErrorBanner(it, onRetry = onRefresh) }

            Header(state)
            Spacer(Modifier.height(16.dp))
            if (state.chart.size >= 2) {
                Sparkline(state.chart, Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 16.dp))
            } else {
                Skeleton(Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 16.dp))
            }
            Text(
                "Live intraday",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            state.holding?.let { h ->
                val last = state.quote?.price ?: h.lastPrice
                val pnl = (last - h.avgPrice) * h.quantity
                SectionCard("Your position") {
                    StatRow("Shares", h.quantity.toString(), "Avg. cost", h.avgPrice.asMoney())
                    StatRow(
                        "Market value", (last * h.quantity).asMoney(),
                        "Total return", pnl.asSignedMoney(), rightColor = changeColor(pnl),
                    )
                }
            }

            SectionCard("Key statistics") {
                val d = state.detail
                if (d == null) {
                    repeat(4) { Skeleton(Modifier.fillMaxWidth().height(20.dp).padding(vertical = 4.dp)) }
                } else {
                    Stats(d)
                }
            }

            state.detail?.let { d ->
                SectionCard("About ${d.name}") {
                    Text(d.sector, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(d.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    state.orderForm?.let { form ->
        OrderSheet(
            form = form,
            price = state.quote?.price ?: 0.0,
            cash = state.cash,
            sharesOwned = state.holding?.quantity ?: 0,
            symbol = state.symbol,
            onQuantity = onQuantity,
            onConfirm = onConfirmOrder,
            onDismiss = onDismissOrder,
        )
    }
}

@Composable
private fun Header(state: StockDetailUiState) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        val name = state.quote?.name?.takeIf { it.isNotBlank() } ?: state.detail?.name
        if (name != null) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Skeleton(Modifier.size(160.dp, 20.dp))
        }
        Spacer(Modifier.height(4.dp))
        val q = state.quote
        if (q != null) {
            LivePrice(q.price, MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(4.dp))
            ChangeChip(q.change, q.changePercent)
        } else {
            Skeleton(Modifier.size(180.dp, 40.dp))
        }
    }
}

@Composable
private fun Stats(d: StockDetail) {
    StatRow("Open", d.open.asMoney(), "Prev. close", d.prevClose.asMoney())
    StatRow("Day high", d.high.asMoney(), "Day low", d.low.asMoney())
    StatRow("52W high", d.week52High.asMoney(), "52W low", d.week52Low.asMoney())
    StatRow("Volume", d.volume.toDouble().asCompact(), "Market cap", "$" + d.marketCap.asCompact())
    StatRow("P/E ratio", String.format(Locale.US, "%.2f", d.peRatio), "Sector", d.sector)
}

@Composable
private fun StatRow(
    leftLabel: String, leftValue: String,
    rightLabel: String, rightValue: String,
    rightColor: Color = Color.Unspecified,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Stat(leftLabel, leftValue, Modifier.weight(1f))
        Stat(rightLabel, rightValue, Modifier.weight(1f), rightColor)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color = Color.Unspecified) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun TradeBar(enabled: Boolean, canSell: Boolean, onBuy: () -> Unit, onSell: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onSell,
                enabled = enabled && canSell,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Sell") }
            Button(
                onClick = onBuy,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = Gain),
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Buy") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderSheet(
    form: OrderForm,
    price: Double,
    cash: Double?,
    sharesOwned: Int,
    symbol: String,
    onQuantity: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        OrderSheetContent(form, price, cash, sharesOwned, symbol, onQuantity, onConfirm)
    }
}

@Composable
private fun OrderSheetContent(
    form: OrderForm,
    price: Double,
    cash: Double?,
    sharesOwned: Int,
    symbol: String,
    onQuantity: (Int) -> Unit,
    onConfirm: () -> Unit,
) {
    val isBuy = form.side == OrderSide.BUY
    val estimate = price * form.quantity
    val validationError = when {
        isBuy && cash != null && estimate > cash -> "Insufficient cash (${cash.asMoney()} available)"
        !isBuy && form.quantity > sharesOwned -> "You only own $sharesOwned shares"
        else -> null
    }

    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Text(
            "${if (isBuy) "Buy" else "Sell"} $symbol",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Market order · ${price.asMoney()} per share",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quantity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = { onQuantity(form.quantity - 1) },
                enabled = !form.isSubmitting && form.quantity > 1,
            ) { Icon(Icons.Default.Remove, "Decrease") }
            Text(
                form.quantity.toString(),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.Center,
            )
            FilledTonalIconButton(
                onClick = { onQuantity(form.quantity + 1) },
                enabled = !form.isSubmitting,
            ) { Icon(Icons.Default.Add, "Increase") }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Row {
            Text("Estimated ${if (isBuy) "cost" else "credit"}", modifier = Modifier.weight(1f))
            Text(estimate.asMoney(), fontWeight = FontWeight.SemiBold)
        }
        Text(
            if (isBuy) "Available cash: ${cash?.asMoney() ?: "…"}" else "Shares owned: $sharesOwned",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Final fill price is set when the order reaches the exchange and may differ from the estimate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        (form.error ?: validationError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onConfirm,
            enabled = !form.isSubmitting && validationError == null && price > 0,
            colors = ButtonDefaults.buttonColors(containerColor = if (isBuy) Gain else Loss),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (form.isSubmitting) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(12.dp))
                Text("Placing order…")
            } else {
                Text(
                    when {
                        form.error != null -> "Retry order"
                        isBuy -> "Confirm buy"
                        else -> "Confirm sell"
                    },
                )
            }
        }
    }
}

private val previewQuote = PreviewData.detail.let { StockQuote(it.symbol, it.name, it.price, it.change, it.changePercent) }

@Composable
private fun StockDetailPreviewScaffold(state: StockDetailUiState) {
    MyApplicationTheme(dynamicColor = false) {
        StockDetailScreen(
            state = state,
            onBack = {}, onRefresh = {}, onOpenOrder = {}, onQuantity = {},
            onConfirmOrder = {}, onDismissOrder = {}, onMessageShown = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun StockDetailScreenPreview() = StockDetailPreviewScaffold(
    StockDetailUiState(
        symbol = "AMZN",
        quote = previewQuote,
        detail = PreviewData.detail,
        chart = PreviewData.history,
        holding = PreviewData.holdings.first().copy(symbol = "AMZN", name = "Amazon.com Inc.", avgPrice = 175.0, lastPrice = 191.09),
        cash = 25_000.0,
        connection = ConnectionState.LIVE,
    ),
)

@Preview(showBackground = true, name = "Loading details (quote already known)")
@Composable
private fun StockDetailScreenLoadingPreview() = StockDetailPreviewScaffold(
    StockDetailUiState(symbol = "AMZN", quote = previewQuote, isLoading = true, connection = ConnectionState.LIVE),
)

@Preview(showBackground = true, name = "Order sheet - buy")
@Composable
private fun OrderSheetBuyPreview() {
    MyApplicationTheme(dynamicColor = false) {
        OrderSheetContent(
            form = OrderForm(OrderSide.BUY, quantity = 5),
            price = 191.09, cash = 25_000.0, sharesOwned = 0, symbol = "AMZN",
            onQuantity = {}, onConfirm = {},
        )
    }
}

@Preview(showBackground = true, name = "Order sheet - submitting")
@Composable
private fun OrderSheetSubmittingPreview() {
    MyApplicationTheme(dynamicColor = false) {
        OrderSheetContent(
            form = OrderForm(OrderSide.SELL, quantity = 3, isSubmitting = true),
            price = 191.09, cash = 25_000.0, sharesOwned = 10, symbol = "AMZN",
            onQuantity = {}, onConfirm = {},
        )
    }
}

@Preview(showBackground = true, name = "Order sheet - failed, retry")
@Composable
private fun OrderSheetErrorPreview() {
    MyApplicationTheme(dynamicColor = false) {
        OrderSheetContent(
            form = OrderForm(OrderSide.BUY, quantity = 2, error = "The server is taking too long to respond."),
            price = 191.09, cash = 25_000.0, sharesOwned = 0, symbol = "AMZN",
            onQuantity = {}, onConfirm = {},
        )
    }
}
