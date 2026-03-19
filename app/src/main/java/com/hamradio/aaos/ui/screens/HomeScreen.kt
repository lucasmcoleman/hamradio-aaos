package com.hamradio.aaos.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hamradio.aaos.radio.protocol.HtStatus
import com.hamradio.aaos.radio.protocol.RfChannel
import com.hamradio.aaos.radio.protocol.SubAudio
import com.hamradio.aaos.radio.transport.ConnectionState
import com.hamradio.aaos.ui.components.BatteryIndicator
import com.hamradio.aaos.ui.components.SignalMeter
import com.hamradio.aaos.ui.components.TxRxIndicator
import com.hamradio.aaos.ui.components.VolumeSlider
import com.hamradio.aaos.ui.theme.Accent
import com.hamradio.aaos.ui.theme.Background
import com.hamradio.aaos.ui.theme.GpsLocked
import com.hamradio.aaos.ui.theme.GpsNone
import com.hamradio.aaos.ui.theme.GpsSearching
import com.hamradio.aaos.ui.theme.OnSurfaceMuted
import com.hamradio.aaos.ui.theme.RxGreen
import com.hamradio.aaos.ui.theme.ScanActive
import com.hamradio.aaos.ui.theme.SurfaceCard
import com.hamradio.aaos.ui.theme.TxRed
import com.hamradio.aaos.vm.MainViewModel

@Composable
fun HomeScreen(vm: MainViewModel, onNavigateToChannels: () -> Unit) {
    val connState  by vm.connectionState.collectAsStateWithLifecycle()
    val htStatus   by vm.htStatus.collectAsStateWithLifecycle()
    val channelA   by vm.activeChannelA.collectAsStateWithLifecycle()
    val channelB   by vm.activeChannelB.collectAsStateWithLifecycle()
    val volume     by vm.volume.collectAsStateWithLifecycle()
    val battery    by vm.batteryPercent.collectAsStateWithLifecycle()
    val isMock     = vm.isMockMode

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ----------------------------------------------------------------
        // Left panel — status + signal
        // ----------------------------------------------------------------
        Column(
            modifier  = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceCard)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top: connection + mock badge
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectionDot(connState)
                    if (isMock) {
                        Text(
                            text  = "MOCK",
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Accent.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                BatteryIndicator(percent = battery)
                GpsIndicator(htStatus.isGpsLocked)
            }

            // Center: TX/RX + signal meter
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TxRxIndicator(
                    isInTx      = htStatus.isInTx,
                    isInRx      = htStatus.isInRx,
                    squelchOpen = htStatus.isSquelchOpen,
                    showLabels  = true,
                )
                SignalMeter(
                    rssi     = htStatus.rssi,
                    isActive = htStatus.isSquelchOpen || htStatus.isInRx,
                    maxHeight = 56.dp,
                )
                AnimatedVisibility(visible = htStatus.isScan) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.Radar, null, tint = ScanActive, modifier = Modifier.size(16.dp))
                        Text("SCAN", style = MaterialTheme.typography.labelLarge, color = ScanActive)
                    }
                }
            }

            // Bottom: volume
            VolumeSlider(
                volume         = volume,
                onVolumeChange = { vm.setVolume(it) },
            )
        }

        // ----------------------------------------------------------------
        // Right panel — channel display (dual watch)
        // ----------------------------------------------------------------
        Column(
            modifier  = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Channel A — primary (taller card)
            ChannelDisplay(
                label      = "A",
                channel    = channelA,
                htStatus   = htStatus,
                isPrimary  = true,
                modifier   = Modifier
                    .weight(1.6f)
                    .fillMaxWidth(),
                onTap      = onNavigateToChannels,
            )
            // Channel B
            ChannelDisplay(
                label      = "B",
                channel    = channelB,
                htStatus   = htStatus,
                isPrimary  = false,
                modifier   = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onTap      = onNavigateToChannels,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Channel display card
// ---------------------------------------------------------------------------

@Composable
private fun ChannelDisplay(
    label:     String,
    channel:   RfChannel?,
    htStatus:  HtStatus,
    isPrimary: Boolean,
    modifier:  Modifier = Modifier,
    onTap:     () -> Unit,
) {
    val isRx = htStatus.isSquelchOpen && !htStatus.isInTx
    val isTx = htStatus.isInTx

    val borderColor by animateColorAsState(
        targetValue = when {
            isTx && isPrimary -> TxRed
            isRx && isPrimary -> RxGreen
            else              -> Color.Transparent
        },
        animationSpec = tween(200),
        label         = "border",
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isTx && isPrimary -> TxRed.copy(alpha = 0.10f)
            isRx && isPrimary -> RxGreen.copy(alpha = 0.08f)
            else              -> SurfaceCard
        },
        animationSpec = tween(200),
        label         = "bg",
    )

    Surface(
        modifier  = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, borderColor, RoundedCornerShape(16.dp)),
        color     = bgColor,
        onClick   = onTap,
    ) {
        Row(
            modifier          = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: label + channel name
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // "A" / "B" badge
                    Box(
                        modifier          = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Accent.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment  = Alignment.Center,
                    ) {
                        Text(
                            text  = label,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Accent,
                        )
                    }

                    // Channel name
                    Text(
                        text     = channel?.name ?: "—",
                        style    = if (isPrimary) MaterialTheme.typography.displaySmall
                                   else MaterialTheme.typography.displayMedium.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color    = if (isTx) TxRed else if (isRx && isPrimary) RxGreen else MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Frequencies
                if (channel != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        FreqText("TX", channel.txFreqMhz, isTx)
                        if (channel.txFreqHz != channel.rxFreqHz) {
                            FreqText("RX", channel.rxFreqMhz, isRx && isPrimary)
                        }
                    }

                    // Sub-audio tone
                    val tone = channel.rxSubAudio
                    if (tone != SubAudio.None) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text  = tone.toneLabel(),
                            style = MaterialTheme.typography.labelMedium,
                            color = OnSurfaceMuted,
                        )
                    }
                }
            }

            // Right: ch number
            if (channel != null) {
                Text(
                    text  = "%02d".format(channel.channelId),
                    style = MaterialTheme.typography.displayMedium,
                    color = OnSurfaceMuted.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun FreqText(label: String, mhz: Double, active: Boolean) {
    Column {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active && label == "TX") TxRed
                    else if (active && label == "RX") RxGreen
                    else OnSurfaceMuted,
        )
        Text(
            text  = "%.4f".format(mhz),
            style = MaterialTheme.typography.displaySmall,
            color = if (active && label == "TX") TxRed
                    else if (active && label == "RX") RxGreen
                    else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ConnectionDot(state: ConnectionState) {
    val (color, label) = when (state) {
        ConnectionState.CONNECTED    -> Accent to "CONNECTED"
        ConnectionState.CONNECTING   -> Accent.copy(alpha = 0.5f) to "CONNECTING"
        ConnectionState.SCANNING     -> GpsSearching to "SCANNING"
        ConnectionState.DISCONNECTED -> OnSurfaceMuted to "DISCONNECTED"
        ConnectionState.ERROR        -> TxRed to "ERROR"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun GpsIndicator(locked: Boolean) {
    val (icon, color, label) = if (locked)
        Triple(Icons.Default.GpsFixed, GpsLocked, "GPS")
    else
        Triple(Icons.Default.GpsNotFixed, GpsSearching, "GPS…")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private fun SubAudio.toneLabel(): String = when (this) {
    is SubAudio.None  -> ""
    is SubAudio.Ctcss -> "CTCSS %.1f Hz".format(hz)
    is SubAudio.Dcs   -> "DCS %03d".format(code)
}
