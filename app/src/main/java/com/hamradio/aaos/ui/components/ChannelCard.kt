package com.hamradio.aaos.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hamradio.aaos.radio.protocol.BandwidthType
import com.hamradio.aaos.radio.protocol.ModulationType
import com.hamradio.aaos.radio.protocol.RfChannel
import com.hamradio.aaos.radio.protocol.SubAudio
import com.hamradio.aaos.ui.theme.Accent
import com.hamradio.aaos.ui.theme.DmrColor
import com.hamradio.aaos.ui.theme.OnSurfaceMuted
import com.hamradio.aaos.ui.theme.Outline
import com.hamradio.aaos.ui.theme.RxGreen
import com.hamradio.aaos.ui.theme.SurfaceCard
import com.hamradio.aaos.ui.theme.SurfaceElevated
import com.hamradio.aaos.ui.theme.TxRed

/**
 * Channel card shown in the channel list and on the home screen.
 *
 * [isActiveA] / [isActiveB]: highlighted border + badge when this is the
 * currently selected Channel A or B.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelCard(
    channel:       RfChannel,
    isActiveA:     Boolean = false,
    isActiveB:     Boolean = false,
    isRx:          Boolean = false,
    isTx:          Boolean = false,
    onClick:       (() -> Unit)? = null,
    onLongClick:   (() -> Unit)? = null,
    modifier:      Modifier = Modifier,
) {
    val borderColor = when {
        isTx      -> TxRed
        isRx      -> RxGreen
        isActiveA -> Accent
        isActiveB -> Accent.copy(alpha = 0.5f)
        else      -> Outline.copy(alpha = 0.4f)
    }
    val containerColor = when {
        isTx      -> TxRed.copy(alpha = 0.08f)
        isRx      -> RxGreen.copy(alpha = 0.08f)
        isActiveA || isActiveB -> SurfaceElevated
        else      -> SurfaceCard
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null || onLongClick != null)
                    Modifier.combinedClickable(
                        onClick     = { onClick?.invoke() },
                        onLongClick = { onLongClick?.invoke() },
                    )
                else Modifier
            ),
        shape  = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isActiveA || isRx || isTx) 2.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Channel number
            Text(
                text  = "%02d".format(channel.channelId),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )

            Spacer(Modifier.width(8.dp))

            // Name + frequencies
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text     = channel.name,
                        style    = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isActiveA) {
                        Spacer(Modifier.width(8.dp))
                        Chip("A", Accent)
                    }
                    if (isActiveB) {
                        Spacer(Modifier.width(4.dp))
                        Chip("B", Accent.copy(alpha = 0.7f))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text  = "TX ${channel.txFreqMhz.formatFreq()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isTx) TxRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (channel.txFreqHz != channel.rxFreqHz) {
                        Text(
                            text  = "RX ${channel.rxFreqMhz.formatFreq()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isRx) RxGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Flags / tags column
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (channel.mute) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Muted",
                            tint = OnSurfaceMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (channel.rxMod == ModulationType.DMR) Chip("DMR", DmrColor)
                    if (channel.bandwidth == BandwidthType.NARROW) Chip("N", MaterialTheme.colorScheme.onSurfaceVariant)
                    if (channel.txDisable) Chip("RO", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val tone = channel.rxSubAudio
                if (tone != SubAudio.None) {
                    Text(
                        text     = tone.label(),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: androidx.compose.ui.graphics.Color) {
    SuggestionChip(
        onClick = {},
        label   = { Text(text, style = MaterialTheme.typography.labelSmall) },
        colors  = SuggestionChipDefaults.suggestionChipColors(
            containerColor  = color.copy(alpha = 0.18f),
            labelColor      = color,
        ),
        border  = SuggestionChipDefaults.suggestionChipBorder(
            enabled         = true,
            borderColor     = color.copy(alpha = 0.4f),
            borderWidth     = 1.dp,
        ),
        modifier = Modifier.height(22.dp),
    )
}

private fun Double.formatFreq(): String = "%.3f".format(this)

private fun SubAudio.label(): String = when (this) {
    is SubAudio.None   -> ""
    is SubAudio.Ctcss  -> "%.1f Hz".format(hz)
    is SubAudio.Dcs    -> "DCS %03d".format(code)
}
