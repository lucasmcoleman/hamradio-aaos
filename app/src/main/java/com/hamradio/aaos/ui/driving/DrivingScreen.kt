package com.hamradio.aaos.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hamradio.aaos.radio.transport.ConnectionState
import com.hamradio.aaos.ui.components.PttButton
import com.hamradio.aaos.ui.theme.Accent
import com.hamradio.aaos.ui.theme.Background
import com.hamradio.aaos.ui.theme.OnBackground
import com.hamradio.aaos.ui.theme.OnSurfaceMuted
import com.hamradio.aaos.ui.theme.RxGreen
import com.hamradio.aaos.ui.theme.SurfaceCard
import com.hamradio.aaos.ui.theme.TxRed
import com.hamradio.aaos.vm.MainViewModel

/**
 * The only screen shown while the head unit requires distraction-optimized UI (vehicle moving).
 * Glanceable status, the active channel, channel up/down and PTT. No text entry, lists or menus;
 * everything else waits until the vehicle is parked.
 */
@Composable
fun DrivingScreen(vm: MainViewModel) {
    val connState  by vm.connectionState.collectAsStateWithLifecycle()
    val htStatus   by vm.htStatus.collectAsStateWithLifecycle()
    val slot       by vm.activeSlot.collectAsStateWithLifecycle()
    val channelA   by vm.activeChannelA.collectAsStateWithLifecycle()
    val channelB   by vm.activeChannelB.collectAsStateWithLifecycle()
    val pttActive  by vm.pttActive.collectAsStateWithLifecycle()
    val toggleMode by vm.pttToggleMode.collectAsStateWithLifecycle()
    val isMock     by vm.mockMode.collectAsStateWithLifecycle()

    val channel = if (slot == "A") channelA else channelB
    val transmitting = htStatus.isInTx || pttActive
    val receiving = htStatus.isSquelchOpen && !transmitting
    val connected = connState == ConnectionState.CONNECTED

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Left: status and the active channel
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = when {
                    !connected   -> "NOT CONNECTED"
                    transmitting -> if (isMock) "LOOPBACK · NO RF" else "TRANSMITTING"
                    receiving    -> "RECEIVING"
                    else         -> if (isMock) "MOCK RADIO" else "STANDBY"
                },
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    !connected   -> OnSurfaceMuted
                    transmitting -> TxRed
                    receiving    -> RxGreen
                    else         -> Accent
                },
            )
            Column {
                Text(
                    text = "$slot  ${channel?.name?.ifBlank { "CH%02d".format(channel.channelId) } ?: "—"}",
                    fontSize = 32.sp,
                    color = OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = channel?.let { "%.3f".format(it.rxFreqMhz) } ?: "—",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp, lineHeight = 76.sp),
                    color = if (transmitting) TxRed else if (receiving) RxGreen else OnBackground,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StepButton(up = true, enabled = connected && !transmitting) { vm.stepChannel(+1) }
                StepButton(up = false, enabled = connected && !transmitting) { vm.stepChannel(-1) }
            }
        }

        // Right: PTT, as big as the screen allows
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            PttButton(
                isTransmitting = transmitting,
                toggleMode     = toggleMode,
                onPttDown      = { vm.pttDown() },
                onPttUp        = { vm.pttUp() },
                onPttToggle    = { vm.pttToggle() },
                modifier       = Modifier.fillMaxSize(),
                labelStyle     = MaterialTheme.typography.displayLarge,
                txLabel        = if (isMock) "LOOPBACK\nNO RF" else "TX",
            )
        }
    }
}

@Composable
private fun StepButton(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick  = onClick,
        enabled  = enabled,
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.size(width = 140.dp, height = 96.dp),
        colors   = ButtonDefaults.filledTonalButtonColors(
            containerColor = SurfaceCard,
            contentColor   = Accent,
        ),
    ) {
        Icon(
            imageVector = if (up) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (up) "Channel up" else "Channel down",
            modifier = Modifier.size(56.dp),
        )
    }
}
