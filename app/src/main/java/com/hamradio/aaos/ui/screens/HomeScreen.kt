package com.hamradio.aaos.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import com.hamradio.aaos.radio.protocol.BandwidthType
import com.hamradio.aaos.radio.transport.BleScanner
import com.hamradio.aaos.radio.protocol.HtStatus
import com.hamradio.aaos.radio.protocol.RfChannel
import com.hamradio.aaos.radio.protocol.SubAudio
import com.hamradio.aaos.vm.MainViewModel.VfoState
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
import com.hamradio.aaos.ui.theme.Outline
import com.hamradio.aaos.ui.theme.RxGreen
import com.hamradio.aaos.ui.theme.ScanActive
import com.hamradio.aaos.ui.theme.SurfaceCard
import com.hamradio.aaos.ui.theme.SurfaceElevated
import com.hamradio.aaos.ui.theme.TxRed
import com.hamradio.aaos.ui.theme.TxRedDim
import com.hamradio.aaos.vm.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onNavigateToChannels: () -> Unit) {
    val connState  by vm.connectionState.collectAsStateWithLifecycle()
    val htStatus   by vm.htStatus.collectAsStateWithLifecycle()
    val channelA   by vm.activeChannelA.collectAsStateWithLifecycle()
    val channelB   by vm.activeChannelB.collectAsStateWithLifecycle()
    val volume     by vm.volume.collectAsStateWithLifecycle()
    val isMock     = vm.isMockMode
    val isVfoA     by vm.isVfoModeA.collectAsStateWithLifecycle()
    val isVfoB     by vm.isVfoModeB.collectAsStateWithLifecycle()
    val vfoA       by vm.vfoA.collectAsStateWithLifecycle()
    val vfoB       by vm.vfoB.collectAsStateWithLifecycle()
    var showConnectionSheet by remember { mutableStateOf(false) }
    val settings   by vm.settings.collectAsStateWithLifecycle()
    val allChannels by vm.channels.collectAsStateWithLifecycle()
    var pttSlot by remember { mutableStateOf("A") }
    val autoSwitchOnRx by vm.autoSwitchOnRx.collectAsStateWithLifecycle()
    var showVfoEditor by remember { mutableStateOf<String?>(null) }

    // Auto-switch slot on RX activity (if enabled and channel not muted)
    LaunchedEffect(autoSwitchOnRx, htStatus.isSquelchOpen, htStatus.isInTx, htStatus.channelId) {
        if (autoSwitchOnRx && htStatus.isSquelchOpen && !htStatus.isInTx) {
            val rxChId = htStatus.channelId
            val rxChannel = allChannels.firstOrNull { it.channelId == rxChId }
            if (rxChannel != null && !rxChannel.mute) {
                val newSlot = when (rxChId) {
                    settings?.channelA -> "A"
                    settings?.channelB -> "B"
                    else -> null
                }
                if (newSlot != null && newSlot != pttSlot) {
                    pttSlot = newSlot
                }
            }
        }
    }

    if (showConnectionSheet) {
        ConnectionSheet(
            vm       = vm,
            onDismiss = { showConnectionSheet = false },
        )
    }

    if (showVfoEditor != null) {
        val slot = showVfoEditor!!
        VfoEditorSheet(
            vfo       = if (slot == "A") vfoA else vfoB,
            slot      = slot,
            onUpdate  = { transform -> vm.updateVfo(slot, transform) },
            onSaveAsChannel = { name ->
                val nextId = (vm.channels.value.maxOfOrNull { it.channelId } ?: -1) + 1
                vm.saveVfoAsChannel(slot, nextId, name)
                // Select the new channel on this slot and switch to CH mode
                if (slot == "A") vm.selectChannelA(nextId) else vm.selectChannelB(nextId)
                vm.toggleVfoMode(slot)
            },
            onDismiss = { showVfoEditor = null },
        )
    }

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
                    ConnectionDot(connState, onTap = { showConnectionSheet = true })
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
                GpsIndicator(htStatus.isGpsLocked, isConnected = htStatus.isPowerOn)
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

            // PTT button
            val pttToggleMode by vm.pttToggleMode.collectAsStateWithLifecycle()
            PttButton(
                isTransmitting = htStatus.isInTx,
                toggleMode     = pttToggleMode,
                onPttDown      = { vm.pttDown() },
                onPttUp        = { vm.pttUp() },
            )

            // Power selector — controls whichever channel is selected by pttSlot
            val activeChannel = if (pttSlot == "A") channelA else channelB
            PowerSelector(
                channel  = activeChannel,
                onSelect = { level ->
                    activeChannel?.let { ch ->
                        val updated = ch.copy(
                            txAtMaxPower = level == 0,
                            txAtMedPower = level == 1,
                        )
                        vm.saveChannel(updated)
                    }
                },
            )

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
            val weightA = if (pttSlot == "A") 1.6f else 1f
            val weightB = if (pttSlot == "B") 1.6f else 1f

            // Slot A
            if (isVfoA) {
                VfoDisplay(
                    label     = "A",
                    vfo       = vfoA,
                    htStatus  = htStatus,
                    isPrimary = pttSlot == "A",
                    modifier  = Modifier.weight(weightA).fillMaxWidth(),
                    onTap     = { pttSlot = "A" },
                    onLongPress = { showVfoEditor = "A" },
                    onModeToggle = { vm.toggleVfoMode("A") },
                )
            } else {
                ChannelDisplay(
                    label      = "A",
                    channel    = channelA,
                    htStatus   = htStatus,
                    isPrimary  = pttSlot == "A",
                    modifier   = Modifier.weight(weightA).fillMaxWidth(),
                    onTap      = { pttSlot = "A" },
                    onLongPress = onNavigateToChannels,
                    onModeToggle = { vm.toggleVfoMode("A") },
                )
            }
            // Slot B
            if (isVfoB) {
                VfoDisplay(
                    label     = "B",
                    vfo       = vfoB,
                    htStatus  = htStatus,
                    isPrimary = pttSlot == "B",
                    modifier  = Modifier.weight(weightB).fillMaxWidth(),
                    onTap     = { pttSlot = "B" },
                    onLongPress = { showVfoEditor = "B" },
                    onModeToggle = { vm.toggleVfoMode("B") },
                )
            } else {
                ChannelDisplay(
                    label      = "B",
                    channel    = channelB,
                    htStatus   = htStatus,
                    isPrimary  = pttSlot == "B",
                    modifier   = Modifier.weight(weightB).fillMaxWidth(),
                    onTap      = { pttSlot = "B" },
                    onLongPress = onNavigateToChannels,
                    onModeToggle = { vm.toggleVfoMode("B") },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Channel display card
// ---------------------------------------------------------------------------

@Composable
private fun ChannelDisplay(
    label:         String,
    channel:       RfChannel?,
    htStatus:      HtStatus,
    isPrimary:     Boolean,
    modifier:      Modifier = Modifier,
    onTap:         () -> Unit,
    onLongPress:   () -> Unit = {},
    onModeToggle:  () -> Unit = {},
) {
    val isThisChannelRx = htStatus.isSquelchOpen && !htStatus.isInTx &&
        channel != null && htStatus.channelId == channel.channelId
    val isThisChannelTx = htStatus.isInTx && isPrimary

    val borderColor by animateColorAsState(
        targetValue = when {
            isThisChannelTx   -> TxRed
            isThisChannelRx   -> RxGreen
            isPrimary         -> Accent.copy(alpha = 0.5f)
            else              -> Color.Transparent
        },
        animationSpec = tween(200),
        label         = "border",
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isThisChannelTx   -> TxRed.copy(alpha = 0.10f)
            isThisChannelRx   -> RxGreen.copy(alpha = 0.08f)
            else              -> SurfaceCard
        },
        animationSpec = tween(200),
        label         = "bg",
    )

    Surface(
        modifier  = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap       = { onTap() },
                    onLongPress = { onLongPress() },
                )
            },
        color     = bgColor,
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
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
                        color    = if (isThisChannelTx) TxRed else if (isThisChannelRx) RxGreen else MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Frequency — show RX normally, TX only during transmit
                if (channel != null) {
                    if (isThisChannelTx && channel.txFreqHz != channel.rxFreqHz) {
                        // Transmitting on a repeater — show TX freq
                        FreqText("TX", channel.txFreqMhz, true)
                    } else {
                        // Normal: show RX freq (or simplex freq)
                        FreqText(
                            if (channel.txFreqHz != channel.rxFreqHz) "RX" else "",
                            channel.rxFreqMhz,
                            isThisChannelRx,
                        )
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

        // Bottom-right CH badge showing current mode; tap to switch to VFO
        if (isPrimary) {
            Text(
                text  = "CH",
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Accent.copy(alpha = 0.15f))
                    .clickable(onClick = onModeToggle)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
      } // Box
    }
}

@Composable
private fun FreqText(label: String, mhz: Double, active: Boolean, onTap: () -> Unit = {}) {
    Column(modifier = Modifier.clickable(onClick = onTap)) {
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
private fun ConnectionDot(state: ConnectionState, onTap: () -> Unit = {}) {
    val (color, label) = when (state) {
        ConnectionState.CONNECTED    -> Accent to "CONNECTED"
        ConnectionState.CONNECTING   -> Accent.copy(alpha = 0.5f) to "CONNECTING"
        ConnectionState.SCANNING     -> GpsSearching to "SCANNING"
        ConnectionState.DISCONNECTED -> OnSurfaceMuted to "DISCONNECTED"
        ConnectionState.ERROR        -> TxRed to "ERROR"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clickable(onClick = onTap),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun GpsIndicator(locked: Boolean, isConnected: Boolean = true) {
    val (icon, color, label) = when {
        !isConnected -> Triple(Icons.Default.GpsOff, GpsNone, "GPS Off")
        locked       -> Triple(Icons.Default.GpsFixed, GpsLocked, "GPS")
        else         -> Triple(Icons.Default.GpsNotFixed, GpsSearching, "GPS…")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp).padding(start = 4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private fun SubAudio.toneLabel(): String = when (this) {
    is SubAudio.None  -> ""
    is SubAudio.Ctcss -> "CTCSS %.1f Hz".format(hz)
    is SubAudio.Dcs   -> "DCS %03d".format(code)
}

// ---------------------------------------------------------------------------
// VFO display card
// ---------------------------------------------------------------------------

@Composable
private fun VfoDisplay(
    label:          String,
    vfo:            VfoState,
    htStatus:       HtStatus,
    isPrimary:      Boolean,
    modifier:       Modifier = Modifier,
    onTap:          () -> Unit,
    onLongPress:    () -> Unit = {},
    onModeToggle:   () -> Unit = {},
) {
    val isTx = htStatus.isInTx
    val isRx = htStatus.isSquelchOpen && !isTx

    val borderColor by animateColorAsState(
        targetValue = when {
            isTx && isPrimary -> TxRed
            isRx && isPrimary -> RxGreen
            isPrimary         -> Accent.copy(alpha = 0.5f)
            else              -> Color.Transparent
        },
        animationSpec = tween(200), label = "vfoBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            isTx && isPrimary -> TxRed.copy(alpha = 0.10f)
            isRx && isPrimary -> RxGreen.copy(alpha = 0.08f)
            else              -> SurfaceCard
        },
        animationSpec = tween(200), label = "vfoBg",
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap       = { onTap() },
                    onLongPress = { onLongPress() },
                )
            },
        color = bgColor,
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Accent.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.headlineSmall, color = Accent)
                    }
                    // Show TX freq during transmit (if offset), otherwise RX freq
                    val displayFreqHz = if (isTx && isPrimary && vfo.offsetHz != 0L) vfo.txFreqHz else vfo.rxFreqHz
                    Text(
                        text  = "%.4f".format(displayFreqHz / 1_000_000.0),
                        style = MaterialTheme.typography.displaySmall,
                        color = if (isTx && isPrimary) TxRed else if (isRx && isPrimary) RxGreen else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val offsetLabel = when {
                        vfo.offsetHz > 0  -> "+%.1f".format(vfo.offsetHz / 1_000_000.0)
                        vfo.offsetHz < 0  -> "%.1f".format(vfo.offsetHz / 1_000_000.0)
                        else              -> "Simplex"
                    }
                    Text(offsetLabel, style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
                    val toneLabel = when (val sa = vfo.txSubAudio) {
                        is SubAudio.None  -> "No Tone"
                        is SubAudio.Ctcss -> "%.1f Hz".format(sa.hz)
                        is SubAudio.Dcs   -> "DCS %03d".format(sa.code)
                    }
                    Text(toneLabel, style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
                    Text(
                        if (vfo.wideBandwidth) "Wide" else "Narrow",
                        style = MaterialTheme.typography.labelMedium,
                        color = OnSurfaceMuted,
                    )
                }
            }
        }

        // Bottom-right VFO badge showing current mode; tap to switch to CH
        if (isPrimary) {
            Text(
                text  = "VFO",
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Accent.copy(alpha = 0.15f))
                    .clickable(onClick = onModeToggle)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
      } // Box
    }
}

// ---------------------------------------------------------------------------
// VFO editor bottom sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VfoEditorSheet(
    vfo: VfoState,
    slot: String,
    onUpdate: ((VfoState) -> VfoState) -> Unit,
    onSaveAsChannel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var freqText by remember(vfo.freqHz) { mutableStateOf("%.4f".format(vfo.freqMhz)) }
    var offsetText by remember(vfo.offsetHz) { mutableStateOf(
        if (vfo.offsetHz == 0L) "" else "%.4f".format(vfo.offsetHz / 1_000_000.0)
    ) }
    var saveName by remember { mutableStateOf("") }

    // Tone state
    var toneType by remember { mutableStateOf(
        when (vfo.txSubAudio) {
            is SubAudio.None -> 0   // None
            is SubAudio.Ctcss -> 1  // CTCSS
            is SubAudio.Dcs -> 2    // DCS
        }
    ) }
    var ctcssText by remember { mutableStateOf(
        when (val sa = vfo.txSubAudio) { is SubAudio.Ctcss -> "%.1f".format(sa.hz); else -> "100.0" }
    ) }
    var dcsText by remember { mutableStateOf(
        when (val sa = vfo.txSubAudio) { is SubAudio.Dcs -> "%03d".format(sa.code); else -> "023" }
    ) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("VFO $slot", style = MaterialTheme.typography.headlineSmall)

            // Frequency
            OutlinedTextField(
                value = freqText,
                onValueChange = { freqText = it },
                label = { Text("Frequency (MHz)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
                modifier = Modifier.fillMaxWidth(),
            )
            freqText.toDoubleOrNull()?.let { mhz ->
                if (!isInKnownBand(mhz)) {
                    Text("Outside known ham/GMRS bands", style = MaterialTheme.typography.labelSmall, color = Accent)
                }
            }

            // Offset
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = offsetText,
                    onValueChange = { offsetText = it },
                    label = { Text("Offset (MHz)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
                    modifier = Modifier.weight(1f),
                )
                // Quick offset buttons
                // Band-aware offset buttons: 600kHz for 2m, 5MHz for 70cm
                val freqMhz = freqText.toDoubleOrNull() ?: 0.0
                val isUhf = freqMhz >= 400.0
                val offsetMhz = if (isUhf) "5.0000" else "0.6000"
                val offsetLabel = if (isUhf) "5 MHz" else "600 kHz"
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(
                        onClick = { offsetText = "+$offsetMhz" },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Accent.copy(0.15f), contentColor = Accent),
                    ) { Text("+$offsetLabel", style = MaterialTheme.typography.labelSmall) }
                    FilledTonalButton(
                        onClick = { offsetText = "-$offsetMhz" },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Accent.copy(0.15f), contentColor = Accent),
                    ) { Text("-$offsetLabel", style = MaterialTheme.typography.labelSmall) }
                    FilledTonalButton(
                        onClick = { offsetText = "" },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Accent.copy(0.15f), contentColor = Accent),
                    ) { Text("Simplex", style = MaterialTheme.typography.labelSmall) }
                }
            }
            // Warn if TX frequency (freq + offset) is out of known bands
            val offsetVal = offsetText.toDoubleOrNull() ?: 0.0
            freqText.toDoubleOrNull()?.let { mhz ->
                val txMhz = mhz + offsetVal
                if (!isInKnownBand(txMhz) && offsetVal != 0.0) {
                    Text("TX freq %.4f outside known ham/GMRS bands".format(txMhz), style = MaterialTheme.typography.labelSmall, color = Accent)
                }
            }

            // Tone selector
            Text("Tone", style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("None" to 0, "CTCSS" to 1, "DCS" to 2).forEach { (label, type) ->
                    val sel = toneType == type
                    FilterChip(
                        selected = sel,
                        onClick  = { toneType = type },
                        label    = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent.copy(alpha = 0.2f),
                            selectedLabelColor     = Accent,
                        ),
                        border   = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = sel,
                            selectedBorderColor = Accent, borderColor = Outline,
                        ),
                    )
                }
            }
            if (toneType == 1) {
                OutlinedTextField(
                    value = ctcssText,
                    onValueChange = { ctcssText = it },
                    label = { Text("CTCSS Hz (e.g. 100.0)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (toneType == 2) {
                OutlinedTextField(
                    value = dcsText,
                    onValueChange = { dcsText = it },
                    label = { Text("DCS Code (e.g. 023)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Bandwidth + Power
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Bandwidth — left aligned
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("BW:", style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
                    listOf("Wide" to true, "Narrow" to false).forEach { (label, wide) ->
                        val sel = vfo.wideBandwidth == wide
                        FilterChip(
                            selected = sel,
                            onClick  = { onUpdate { it.copy(wideBandwidth = wide) } },
                            label    = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent.copy(alpha = 0.2f),
                                selectedLabelColor     = Accent,
                            ),
                            border   = FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = sel,
                                selectedBorderColor = Accent, borderColor = Outline,
                            ),
                        )
                    }
                }
                // Power — right aligned
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Power:", style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
                    listOf("High" to true, "Low" to false).forEach { (label, high) ->
                        val sel = vfo.highPower == high
                        FilterChip(
                            selected = sel,
                            onClick  = { onUpdate { it.copy(highPower = high) } },
                            label    = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent.copy(alpha = 0.2f),
                                selectedLabelColor     = Accent,
                            ),
                            border   = FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = sel,
                                selectedBorderColor = Accent, borderColor = Outline,
                            ),
                        )
                    }
                }
            }

            // Apply frequency + offset + tone
            FilledTonalButton(
                onClick = {
                    val freq = freqText.toDoubleOrNull() ?: return@FilledTonalButton
                    val offset = offsetText.toDoubleOrNull() ?: 0.0
                    val tone = when (toneType) {
                        1 -> ctcssText.toFloatOrNull()?.let { SubAudio.Ctcss(it) } ?: SubAudio.None
                        2 -> dcsText.toIntOrNull()?.let { SubAudio.Dcs(it) } ?: SubAudio.None
                        else -> SubAudio.None
                    }
                    onUpdate { it.copy(
                        freqHz = (freq * 1_000_000).toLong(),
                        offsetHz = (offset * 1_000_000).toLong(),
                        txSubAudio = tone,
                        rxSubAudio = tone,
                    ) }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Accent.copy(0.2f), contentColor = Accent),
            ) { Text("Apply") }

            HorizontalDivider(color = Outline)

            // Save as channel
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { if (it.length <= 10) saveName = it.uppercase() },
                    label = { Text("Channel Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = {
                        if (saveName.isNotBlank()) {
                            onSaveAsChannel(saveName)
                            onDismiss()
                        }
                    },
                    enabled = saveName.isNotBlank(),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Accent.copy(0.2f), contentColor = Accent),
                ) { Text("Save CH") }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Frequency input dialog — writes to the channel via WRITE_RF_CH
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// PTT Button — press-and-hold to transmit
// ---------------------------------------------------------------------------

@Composable
private fun PttButton(
    isTransmitting: Boolean,
    toggleMode: Boolean = false,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
) {
    val bgColor = if (isTransmitting) TxRed else TxRedDim
    val textColor = if (isTransmitting) Color.White else TxRed

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .pointerInput(toggleMode) {
                if (toggleMode) {
                    // Tap on / tap off
                    detectTapGestures(
                        onTap = {
                            if (isTransmitting) onPttUp() else onPttDown()
                        },
                    )
                } else {
                    // Momentary: hold to transmit
                    detectTapGestures(
                        onPress = {
                            onPttDown()
                            tryAwaitRelease()
                            onPttUp()
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = if (isTransmitting) "TX" else "PTT",
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
        )
    }
}

/** 3-button power selector: High / Med / Low — shows current channel's power highlighted. */
@Composable
private fun PowerSelector(channel: RfChannel?, onSelect: (Int) -> Unit) {
    Column {
        Text(
            "TX POWER",
            style = MaterialTheme.typography.labelLarge,
            color = OnSurfaceMuted,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
    val current = when {
        channel == null            -> 0
        channel.txAtMaxPower       -> 0
        channel.txAtMedPower       -> 1
        else                       -> 2
    }
    val labels = listOf("High", "Med", "Low")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { idx, label ->
            val selected = idx == current
            val color = when {
                selected && idx == 0 -> TxRed
                selected             -> Accent
                else                 -> OnSurfaceMuted
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) color.copy(alpha = 0.15f) else Color.Transparent)
                    .border(
                        width = if (selected) 1.dp else 0.dp,
                        color = if (selected) color.copy(alpha = 0.5f) else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(idx) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
            }
        }
    }
    } // end Column wrapper
}

// ---------------------------------------------------------------------------
// Connection bottom sheet (H2)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSheet(
    vm: MainViewModel,
    onDismiss: () -> Unit,
) {
    val connState by vm.connectionState.collectAsStateWithLifecycle()
    val isMock = vm.isMockMode
    var addressText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = SurfaceCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Text("Connection", style = MaterialTheme.typography.headlineSmall)

            // Status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val stateColor = when (connState) {
                        ConnectionState.CONNECTED    -> Accent
                        ConnectionState.CONNECTING,
                        ConnectionState.SCANNING     -> GpsSearching
                        ConnectionState.DISCONNECTED -> OnSurfaceMuted
                        ConnectionState.ERROR        -> TxRed
                    }
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    Text(
                        text  = connState.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = stateColor,
                    )
                }

                // Transport badge
                Text(
                    text  = if (isMock) "MOCK" else "BLE",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isMock) Accent else RxGreen,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isMock) Accent.copy(0.15f) else RxGreen.copy(0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            HorizontalDivider(color = Outline)

            // Retry / Connect / Disconnect
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (connState == ConnectionState.CONNECTED) {
                    OutlinedButton(
                        onClick  = { vm.disconnect() },
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = TxRed),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, TxRed.copy(0.5f)),
                    ) { Text("Disconnect") }
                } else {
                    FilledTonalButton(
                        onClick  = { vm.connect() },
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Accent.copy(alpha = 0.2f),
                            contentColor   = Accent,
                        ),
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (connState == ConnectionState.ERROR) "Retry" else "Connect")
                    }
                }
            }

            HorizontalDivider(color = Outline)

            // Device scan + manual entry (BLE mode only)
            if (!isMock) {
                val context = LocalContext.current
                val scanner = remember { BleScanner(context) }
                val scannedDevices by scanner.devices.collectAsStateWithLifecycle()
                val isScanning by scanner.isScanning.collectAsStateWithLifecycle()

                // Runtime BLE permission request (required on Android 12+)
                val permissionLauncher = rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    if (grants.values.all { it }) scanner.startScan()
                }
                val blePermissions = remember {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
                    else
                        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }

                fun startScanWithPermission() {
                    val allGranted = blePermissions.all {
                        androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (allGranted) scanner.startScan()
                    else permissionLauncher.launch(blePermissions)
                }

                // Stop scan when sheet closes
                DisposableEffect(Unit) { onDispose { scanner.stopScan() } }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Nearby Devices", style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
                    FilledTonalButton(
                        onClick = { if (isScanning) scanner.stopScan() else startScanWithPermission() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Accent.copy(alpha = 0.2f),
                            contentColor = Accent,
                        ),
                    ) { Text(if (isScanning) "Stop" else "Scan") }
                }

                if (scannedDevices.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 160.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(scannedDevices) { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceElevated)
                                    .clickable {
                                        scanner.stopScan()
                                        vm.connectToDevice(device.address)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(device.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(device.address, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(device.type, style = MaterialTheme.typography.labelSmall, color = Accent)
                                    if (device.rssi != 0) {
                                        Text("${device.rssi} dBm", style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
                                    }
                                }
                            }
                        }
                    }
                } else if (isScanning) {
                    Text("Scanning…", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
                }

                HorizontalDivider(color = Outline.copy(alpha = 0.3f))

                // Manual MAC fallback
                Text("Manual Address", style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it.uppercase() },
                        placeholder = { Text("AA:BB:CC:DD:EE:FF", color = OnSurfaceMuted) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Bluetooth, null, tint = OnSurfaceMuted) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            cursorColor = Accent,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    FilledTonalButton(
                        onClick = {
                            if (addressText.isNotBlank()) {
                                vm.connectToDevice(addressText.trim())
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Accent.copy(alpha = 0.2f),
                            contentColor   = Accent,
                        ),
                    ) { Text("Set") }
                }
            }

            // Mock mode toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Mock Radio", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = isMock,
                    onCheckedChange = { vm.toggleMockMode() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Accent,
                        checkedTrackColor = Accent.copy(0.4f),
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
