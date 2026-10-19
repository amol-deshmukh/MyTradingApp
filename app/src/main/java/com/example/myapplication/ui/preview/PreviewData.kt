package com.example.myapplication.ui.preview

import com.example.myapplication.data.model.Holding
import com.example.myapplication.data.model.StockDetail
import com.example.myapplication.data.model.StockQuote
import kotlin.math.sin

/** Static sample data for Compose @Previews. */
object PreviewData {
    val quotes = listOf(
        StockQuote("AAPL", "Apple Inc.", 233.88, 5.08, 2.22),
        StockQuote("MSFT", "Microsoft Corp.", 444.07, 10.11, 2.33),
        StockQuote("GOOGL", "Alphabet Inc.", 171.77, -0.40, -0.23),
        StockQuote("AMZN", "Amazon.com Inc.", 187.75, -2.28, -1.20),
        StockQuote("NVDA", "NVIDIA Corp.", 137.74, 2.40, 1.77),
        StockQuote("TSLA", "Tesla Inc.", 259.97, 5.91, 2.33),
        StockQuote("META", "Meta Platforms", 568.72, 7.85, 1.40),
        StockQuote("NFLX", "Netflix Inc.", 702.16, -3.10, -0.44),
    )

    val history = List(120) { i -> 190.0 + i * 0.01 + sin(i / 6.0) * 1.5 }

    val detail = StockDetail(
        symbol = "AMZN",
        name = "Amazon.com Inc.",
        price = 191.09,
        change = 1.06,
        changePercent = 0.56,
        sector = "Consumer",
        description = "E-commerce, AWS cloud computing, digital streaming and devices.",
        open = 190.33,
        high = 191.81,
        low = 185.80,
        prevClose = 190.03,
        volume = 16_980_000,
        marketCap = 2.01e12,
        peRatio = 41.5,
        week52High = 235.73,
        week52Low = 146.94,
        history = history,
    )

    val holdings = listOf(
        Holding("AAPL", "Apple Inc.", 20, 210.15, 233.88),
        Holding("NVDA", "NVIDIA Corp.", 35, 118.40, 137.74),
        Holding("KO", "Coca-Cola Co.", 50, 72.20, 69.80),
    )
}
