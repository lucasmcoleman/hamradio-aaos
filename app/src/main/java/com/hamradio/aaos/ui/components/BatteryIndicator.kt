package com.hamradio.aaos.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryUnknown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hamradio.aaos.ui.theme.BatteryCritical
import com.hamradio.aaos.ui.theme.BatteryHigh
import com.hamradio.aaos.ui.theme.BatteryLow
import com.hamradio.aaos.ui.theme.BatteryMid

@Composable
fun BatteryIndicator(
    percent:  Int,
    modifier: Modifier = Modifier,
    showText: Boolean  = true,
) {
    val color by animateColorAsState(
        targetValue = when {
            percent < 0  -> MaterialTheme.colorScheme.onSurfaceVariant
            percent < 10 -> BatteryCritical
            percent < 25 -> BatteryLow
            percent < 50 -> BatteryMid
            else         -> BatteryHigh
        },
        animationSpec = tween(400),
        label         = "battColor",
    )

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector        = percent.batteryIcon(),
            contentDescription = "Battery $percent%",
            tint               = color,
        )
        if (showText && percent >= 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text  = "$percent%",
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}

private fun Int.batteryIcon(): ImageVector = when {
    this < 0  -> Icons.Default.BatteryUnknown
    this < 5  -> Icons.Default.Battery0Bar
    this < 15 -> Icons.Default.Battery1Bar
    this < 30 -> Icons.Default.Battery2Bar
    this < 45 -> Icons.Default.Battery3Bar
    this < 60 -> Icons.Default.Battery4Bar
    this < 75 -> Icons.Default.Battery5Bar
    this < 90 -> Icons.Default.Battery6Bar
    else      -> Icons.Default.BatteryFull
}
