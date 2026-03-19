package com.hamradio.aaos.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.hamradio.aaos.ui.theme.OnSurfaceMuted
import com.hamradio.aaos.ui.theme.RxGreen
import com.hamradio.aaos.ui.theme.SqAmber
import com.hamradio.aaos.ui.theme.TxRed

/**
 * Combined TX / RX / SQ indicator pill.
 *
 * Color semantics (single source of truth — always use these):
 *   TX active  → [TxRed]   with pulse animation
 *   RX active  → [RxGreen] with pulse animation
 *   Squelch    → [SqAmber] static
 *   Idle       → dim grey
 */
@Composable
fun TxRxIndicator(
    isInTx:        Boolean,
    isInRx:        Boolean,
    squelchOpen:   Boolean,
    modifier:      Modifier = Modifier,
    dotSize:       Dp = 12.dp,
    showLabels:    Boolean = true,
) {
    Row(
        modifier       = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            active  = isInTx,
            color   = TxRed,
            label   = if (showLabels) "TX" else null,
            pulse   = isInTx,
            dotSize = dotSize,
        )
        if (showLabels) Spacer(Modifier.width(16.dp))
        StatusDot(
            active  = isInRx || squelchOpen,
            color   = if (isInRx) RxGreen else SqAmber,
            label   = if (showLabels) "RX" else null,
            pulse   = isInRx,
            dotSize = dotSize,
        )
    }
}

@Composable
private fun StatusDot(
    active:  Boolean,
    color:   Color,
    label:   String?,
    pulse:   Boolean,
    dotSize: Dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by if (pulse) {
        infiniteTransition.animateFloat(
            initialValue   = 1f,
            targetValue    = 0.25f,
            animationSpec  = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
            label          = "alpha",
        )
    } else {
        infiniteTransition.animateFloat(1f, 1f, infiniteRepeatable(tween(1000)), label = "alpha_static")
    }

    val dotColor by animateColorAsState(
        targetValue    = if (active) color.copy(alpha = alpha) else OnSurfaceMuted.copy(alpha = 0.3f),
        animationSpec  = tween(200),
        label          = "dotColor",
    )
    val textColor by animateColorAsState(
        targetValue    = if (active) color else OnSurfaceMuted,
        animationSpec  = tween(200),
        label          = "textColor",
    )

    if (label != null) {
        Row(
            modifier          = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (active) color.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text  = label,
                color = textColor,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    } else {
        Box(
            Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}
