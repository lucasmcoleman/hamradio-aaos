package com.hamradio.aaos.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hamradio.aaos.ui.theme.Outline
import com.hamradio.aaos.ui.theme.RxGreen
import com.hamradio.aaos.ui.theme.SignalFull
import com.hamradio.aaos.ui.theme.SignalLow
import com.hamradio.aaos.ui.theme.SignalMid
import com.hamradio.aaos.ui.theme.SignalNone

private const val MAX_RSSI = 15
private const val BARS = 15

/**
 * Animated RSSI bar meter (S-meter style).
 * rssi: 0–15 matching the radio's reported value.
 * RX active uses [RxGreen]; idle bars are [Outline].
 */
@Composable
fun SignalMeter(
    rssi:     Int,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    barWidth: Dp = 6.dp,
    maxHeight: Dp = 36.dp,
    label:    Boolean = true,
) {
    val animatedRssi by animateFloatAsState(
        targetValue   = rssi.coerceIn(0, MAX_RSSI).toFloat(),
        animationSpec = tween(300),
        label         = "rssi",
    )

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment     = Alignment.Bottom,
            modifier              = Modifier.height(maxHeight),
        ) {
            for (i in 1..BARS) {
                val barHeightFraction = i.toFloat() / BARS
                val filled = i <= animatedRssi
                val barColor = when {
                    !filled   -> SignalNone
                    !isActive -> Outline
                    i <= 5    -> SignalFull
                    i <= 10   -> SignalMid
                    else      -> SignalLow
                }
                Box(
                    modifier = Modifier
                        .width(barWidth)
                        .fillMaxHeight(barHeightFraction)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(barColor)
                )
            }
        }
        if (label) {
            Spacer(Modifier.height(4.dp))
            Text(
                text  = if (isActive) "S${rssi.coerceIn(0, 9)}" else "S0",
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) RxGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
