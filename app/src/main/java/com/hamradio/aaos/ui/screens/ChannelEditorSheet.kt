package com.hamradio.aaos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hamradio.aaos.radio.protocol.BandwidthType
import com.hamradio.aaos.radio.protocol.RfChannel
import com.hamradio.aaos.radio.protocol.SubAudio
import com.hamradio.aaos.ui.theme.Accent
import com.hamradio.aaos.ui.theme.OnSurfaceMuted
import com.hamradio.aaos.ui.theme.Outline
import com.hamradio.aaos.ui.theme.SurfaceCard
import com.hamradio.aaos.ui.theme.TxRed

// Standard CTCSS tones
private val CTCSS_TONES = listOf(
    67.0f, 69.3f, 71.9f, 74.4f, 77.0f, 79.7f, 82.5f, 85.4f, 88.5f, 91.5f,
    94.8f, 97.4f, 100.0f, 103.5f, 107.2f, 110.9f, 114.8f, 118.8f, 123.0f, 127.3f,
    131.8f, 136.5f, 141.3f, 146.2f, 151.4f, 156.7f, 159.8f, 162.2f, 165.5f, 167.9f,
    171.3f, 173.8f, 177.3f, 179.9f, 183.5f, 186.2f, 189.9f, 192.8f, 196.6f, 199.5f,
    203.5f, 206.5f, 210.7f, 218.1f, 225.7f, 229.1f, 233.6f, 241.8f, 250.3f, 254.1f,
)

// Ham + GMRS bands for warning (not blocking)
val KNOWN_BANDS = listOf(
    136.0..174.0,   // VHF (2m ham + commercial)
    222.0..225.0,   // 1.25m ham
    400.0..470.0,   // UHF (70cm ham + GMRS/FRS)
    462.5625..462.7250, // GMRS repeater inputs
    467.5625..467.7250, // GMRS repeater outputs
    902.0..928.0,   // 33cm ham
)

fun isInKnownBand(mhz: Double): Boolean = KNOWN_BANDS.any { mhz in it }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChannelEditorSheet(
    channel: RfChannel,
    onSave: (RfChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    // Mutable edit state initialized from channel
    var name by remember { mutableStateOf(channel.name) }
    var txFreq by remember { mutableStateOf("%.4f".format(channel.txFreqMhz)) }
    var rxFreq by remember { mutableStateOf("%.4f".format(channel.rxFreqMhz)) }
    // 0=High(50W), 1=Medium(25W), 2=Low(8W)
    var powerLevel by remember { mutableStateOf(
        when { channel.txAtMaxPower -> 0; channel.txAtMedPower -> 1; else -> 2 }
    ) }
    var bandwidth by remember { mutableStateOf(channel.bandwidth) }
    var scanEnabled by remember { mutableStateOf(channel.scan) }
    var txDisable by remember { mutableStateOf(channel.txDisable) }
    var talkAround by remember { mutableStateOf(channel.talkAround) }
    var muted by remember { mutableStateOf(channel.mute) }

    // Sub-audio state
    var txSubType by remember { mutableStateOf(subAudioType(channel.txSubAudio)) }
    var txCtcss by remember { mutableStateOf(ctcssHz(channel.txSubAudio)) }
    var txDcs by remember { mutableStateOf(dcsCode(channel.txSubAudio)) }
    var rxSubType by remember { mutableStateOf(subAudioType(channel.rxSubAudio)) }
    var rxCtcss by remember { mutableStateOf(ctcssHz(channel.rxSubAudio)) }
    var rxDcs by remember { mutableStateOf(dcsCode(channel.rxSubAudio)) }

    val txMhz = txFreq.toDoubleOrNull()
    val rxMhz = rxFreq.toDoubleOrNull()
    val txOutOfBand = txMhz != null && !isInKnownBand(txMhz)
    val rxOutOfBand = rxMhz != null && !isInKnownBand(rxMhz)
    val canSave = name.isNotBlank() && txMhz != null && rxMhz != null

    fun buildAndSave() {
        if (canSave) {
            onSave(channel.copy(
                name = name.take(10),
                txFreqHz = (txMhz!! * 1_000_000).toLong(),
                rxFreqHz = (rxMhz!! * 1_000_000).toLong(),
                txSubAudio = buildSubAudio(txSubType, txCtcss, txDcs),
                rxSubAudio = buildSubAudio(rxSubType, rxCtcss, rxDcs),
                txAtMaxPower = powerLevel == 0,
                txAtMedPower = powerLevel == 1,
                bandwidth = bandwidth,
                scan = scanEnabled,
                txDisable = txDisable,
                talkAround = talkAround,
                mute = muted,
            ))
        } else {
            onDismiss()
        }
    }

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
            Text(
                "Edit Channel ${channel.channelId}",
                style = MaterialTheme.typography.headlineSmall,
            )

            HorizontalDivider(color = Outline)

            // --- Name ---
            EditorField("Name", name, 10) { name = it.uppercase() }

            // --- Frequencies ---
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    EditorField("TX MHz", txFreq, 10, KeyboardType.Decimal) { txFreq = it }
                    if (txOutOfBand) {
                        Text("Outside known ham/GMRS bands", style = MaterialTheme.typography.labelSmall, color = Accent)
                    }
                }
                Column(Modifier.weight(1f)) {
                    EditorField("RX MHz", rxFreq, 10, KeyboardType.Decimal) { rxFreq = it }
                    if (rxOutOfBand) {
                        Text("Outside known ham/GMRS bands", style = MaterialTheme.typography.labelSmall, color = Accent)
                    }
                }
            }

            HorizontalDivider(color = Outline.copy(alpha = 0.3f))

            // --- TX Sub-Audio ---
            Text("TX Tone", style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
            SubAudioSelector(
                type = txSubType,
                ctcssHz = txCtcss,
                dcsCode = txDcs,
                onTypeChange = { txSubType = it },
                onCtcssChange = { txCtcss = it },
                onDcsChange = { txDcs = it },
            )

            // --- RX Sub-Audio ---
            Text("RX Tone", style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
            SubAudioSelector(
                type = rxSubType,
                ctcssHz = rxCtcss,
                dcsCode = rxDcs,
                onTypeChange = { rxSubType = it },
                onCtcssChange = { rxCtcss = it },
                onDcsChange = { rxDcs = it },
            )

            HorizontalDivider(color = Outline.copy(alpha = 0.3f))

            // --- Toggles row ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CycleCol(
                    label = "Power",
                    value = when (powerLevel) { 0 -> "50W"; 1 -> "25W"; else -> "8W" },
                    onClick = { powerLevel = (powerLevel + 1) % 3 },
                )
                ToggleCol("Wide BW", bandwidth == BandwidthType.WIDE) {
                    bandwidth = if (it) BandwidthType.WIDE else BandwidthType.NARROW
                }
                ToggleCol("Scan", scanEnabled) { scanEnabled = it }
                ToggleCol("TX Off", txDisable) { txDisable = it }
                ToggleCol("Mute", muted) { muted = it }
            }

            Spacer(Modifier.height(8.dp))

            // --- Save ---
            FilledTonalButton(
                onClick = { buildAndSave() },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Accent.copy(alpha = 0.2f),
                    contentColor = Accent,
                ),
            ) {
                Text("Save Channel")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-audio selector (None / CTCSS / DCS)
// ---------------------------------------------------------------------------

private enum class SubAudioType { NONE, CTCSS, DCS }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubAudioSelector(
    type: SubAudioType,
    ctcssHz: Float,
    dcsCode: Int,
    onTypeChange: (SubAudioType) -> Unit,
    onCtcssChange: (Float) -> Unit,
    onDcsChange: (Int) -> Unit,
) {
    // Type selector
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SubAudioType.entries.forEach { t ->
            FilterChip(
                selected = type == t,
                onClick = { onTypeChange(t) },
                label = { Text(t.name, style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent.copy(alpha = 0.2f),
                    selectedLabelColor = Accent,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = type == t,
                    selectedBorderColor = Accent,
                    borderColor = Outline,
                ),
            )
        }
    }

    when (type) {
        SubAudioType.CTCSS -> {
            // Show common tones as chips + current selection
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CTCSS_TONES.forEach { hz ->
                    val sel = ctcssHz == hz
                    FilterChip(
                        selected = sel,
                        onClick = { onCtcssChange(hz) },
                        label = { Text("%.1f".format(hz), style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent.copy(alpha = 0.2f),
                            selectedLabelColor = Accent,
                            labelColor = OnSurfaceMuted,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = sel,
                            selectedBorderColor = Accent,
                            borderColor = Outline.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
        }
        SubAudioType.DCS -> {
            var dcsText by remember(dcsCode) { mutableStateOf(if (dcsCode > 0) "%03d".format(dcsCode) else "") }
            EditorField("DCS Code", dcsText, 3, KeyboardType.Number) { v ->
                dcsText = v
                v.toIntOrNull()?.let { if (it in 1..777) onDcsChange(it) }
            }
        }
        SubAudioType.NONE -> { /* nothing */ }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@Composable
private fun EditorField(
    label: String,
    value: String,
    maxLen: Int,
    kbType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLen) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = kbType,
            capitalization = if (kbType == KeyboardType.Text) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            cursorColor = Accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CycleCol(label: String, value: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalButton(
            onClick = onClick,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = Accent.copy(alpha = 0.15f),
                contentColor = Accent,
            ),
        ) { Text(value, style = MaterialTheme.typography.labelMedium) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
    }
}

@Composable
private fun ToggleCol(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Accent,
                checkedTrackColor = Accent.copy(0.4f),
            ),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
    }
}

private fun subAudioType(sa: SubAudio): SubAudioType = when (sa) {
    is SubAudio.None -> SubAudioType.NONE
    is SubAudio.Ctcss -> SubAudioType.CTCSS
    is SubAudio.Dcs -> SubAudioType.DCS
}

private fun ctcssHz(sa: SubAudio): Float = when (sa) {
    is SubAudio.Ctcss -> sa.hz
    else -> 100.0f
}

private fun dcsCode(sa: SubAudio): Int = when (sa) {
    is SubAudio.Dcs -> sa.code
    else -> 23
}

private fun buildSubAudio(type: SubAudioType, ctcss: Float, dcs: Int): SubAudio = when (type) {
    SubAudioType.NONE -> SubAudio.None
    SubAudioType.CTCSS -> SubAudio.Ctcss(ctcss)
    SubAudioType.DCS -> SubAudio.Dcs(dcs)
}
