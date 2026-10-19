package com.example.myapplication.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.model.ConnectionState
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

val Gain = Color(0xFF1B9E5A)
val Loss = Color(0xFFD93F3F)

private val currency = NumberFormat.getCurrencyInstance(Locale.US)

fun Double.asMoney(): String = currency.format(this)
fun Double.asSignedMoney(): String = (if (this >= 0) "+" else "-") + currency.format(abs(this))
fun Double.asSignedPercent(): String = String.format(Locale.US, "%+.2f%%", this)
fun Double.asCompact(): String = when {
    this >= 1e12 -> String.format(Locale.US, "%.2fT", this / 1e12)
    this >= 1e9 -> String.format(Locale.US, "%.2fB", this / 1e9)
    this >= 1e6 -> String.format(Locale.US, "%.2fM", this / 1e6)
    this >= 1e3 -> String.format(Locale.US, "%.1fK", this / 1e3)
    else -> String.format(Locale.US, "%.0f", this)
}

fun changeColor(value: Double): Color = if (value >= 0) Gain else Loss

/** Price text that briefly flashes green/red when the value ticks up/down. */
@Composable
fun LivePrice(price: Double, style: TextStyle, modifier: Modifier = Modifier) {
    var previous by remember { mutableStateOf(price) }
    var flash by remember { mutableStateOf(Color.Transparent) }
    LaunchedEffect(price) {
        if (price != previous) {
            flash = (if (price > previous) Gain else Loss).copy(alpha = 0.25f)
            previous = price
            kotlinx.coroutines.delay(400)
            flash = Color.Transparent
        }
    }
    val bg by animateColorAsState(flash, tween(300), label = "flash")
    Text(
        text = price.asMoney(),
        style = style,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 4.dp),
    )
}

@Composable
fun ChangeChip(change: Double, changePercent: Double, modifier: Modifier = Modifier) {
    val color = changeColor(change)
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            text = "${change.asSignedMoney()} (${changePercent.asSignedPercent()})",
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Small "LIVE / Reconnecting / Offline" pill reflecting the WebSocket state. */
@Composable
fun ConnectionPill(state: ConnectionState, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        ConnectionState.LIVE -> "LIVE" to Gain
        ConnectionState.CONNECTING -> "Connecting" to Color(0xFFE0A100)
        ConnectionState.RECONNECTING -> "Reconnecting" to Color(0xFFE0A100)
        ConnectionState.OFFLINE -> "Offline" to Loss
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = if (state == ConnectionState.LIVE) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    )
    Row(modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun Sparkline(points: List<Double>, modifier: Modifier = Modifier, strokeWidth: Dp = 2.dp) {
    if (points.size < 2) return
    val color = changeColor(points.last() - points.first())
    Canvas(modifier) {
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, p ->
            val o = Offset(i * stepX, (size.height * (1 - (p - min) / range)).toFloat())
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, color, style = Stroke(width = strokeWidth.toPx()))
    }
}

/** Pulsing placeholder block shown while slow requests are in flight. */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val alpha by t.animateFloat(0.4f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f * alpha)),
    )
}

@Composable
fun ErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
