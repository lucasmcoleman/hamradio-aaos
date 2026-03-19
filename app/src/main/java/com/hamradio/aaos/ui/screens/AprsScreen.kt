package com.hamradio.aaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hamradio.aaos.ui.theme.Accent
import com.hamradio.aaos.ui.theme.AprsColor
import com.hamradio.aaos.ui.theme.Background
import com.hamradio.aaos.ui.theme.OnSurfaceMuted
import com.hamradio.aaos.ui.theme.Outline
import com.hamradio.aaos.ui.theme.SurfaceCard
import com.hamradio.aaos.vm.MainViewModel

@Composable
fun AprsScreen(vm: MainViewModel) {
    Text("APRS placeholder", color = androidx.compose.ui.graphics.Color.White)
    return
    @Suppress("UNREACHABLE_CODE")
    val bss by vm.bssSettings.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    // Edit dialog state
    var editField by remember { mutableStateOf<String?>(null) }
    var editValue by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("APRS / BSS", style = MaterialTheme.typography.headlineMedium)
            Text(
                text  = if ((bss?.packetFormat ?: 0) == 1) "APRS" else "BSS",
                style = MaterialTheme.typography.labelLarge,
                color = AprsColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AprsColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        if (bss == null) {
            Text("Waiting for radio…", style = MaterialTheme.typography.bodyLarge, color = OnSurfaceMuted)
            return@Column
        }

        val b = bss!!

        // Station identity
        AprsGroup("Station Identity") {
            EditableAprsRow("Callsign", b.aprsCallsign.ifBlank { "—" }) {
                editField = "callsign"; editValue = b.aprsCallsign
            }
            EditableAprsRow("SSID", "-${b.aprsSsid}") {
                editField = "ssid"; editValue = b.aprsSsid.toString()
            }
            EditableAprsRow("Symbol", b.aprsSymbol.ifBlank { "—" }) {
                editField = "symbol"; editValue = b.aprsSymbol
            }
            EditableAprsRow("ID Info", b.pttReleaseIdInfo.ifBlank { "—" }) {
                editField = "idinfo"; editValue = b.pttReleaseIdInfo
            }
        }

        // Beacon
        AprsGroup("Beacon") {
            EditableAprsRow("Message", b.beaconMessage.ifBlank { "(none)" }) {
                editField = "beacon"; editValue = b.beaconMessage
            }
            EditableAprsRow("Interval", "${b.locationShareIntervalSec}s") {
                editField = "interval"; editValue = b.locationShareIntervalSec.toString()
            }
            EditableAprsRow("TTL", b.timeToLive.toString()) {
                editField = "ttl"; editValue = b.timeToLive.toString()
            }
            EditableAprsRow("Max Forwards", b.maxFwdTimes.toString()) {
                editField = "maxfwd"; editValue = b.maxFwdTimes.toString()
            }
            ToggleRow("Share Location",    b.shouldShareLocation)  { v -> vm.updateBssSettings { it.copy(shouldShareLocation = v) } }
            ToggleRow("Send PTT Location", b.pttReleaseSendLocation) { v -> vm.updateBssSettings { it.copy(pttReleaseSendLocation = v) } }
            ToggleRow("Send ID on PTT",    b.pttReleaseSendIdInfo) { v -> vm.updateBssSettings { it.copy(pttReleaseSendIdInfo = v) } }
        }

        // Power / position
        AprsGroup("Options") {
            ToggleRow("Send Power/Voltage",  b.sendPwrVoltage)     { v -> vm.updateBssSettings { it.copy(sendPwrVoltage = v) } }
            ToggleRow("Allow Position Check", b.allowPositionCheck) { v -> vm.updateBssSettings { it.copy(allowPositionCheck = v) } }
        }

        // APRS channel assignment
        val chB = settings?.channelB?.let { id -> channels.firstOrNull { it.channelId == id } }
        AprsGroup("APRS Channel") {
            AprsInfoRow("Assigned Slot", "Channel B")
            if (chB != null) {
                AprsInfoRow("Current B", "${chB.name} — %.4f MHz".format(chB.txFreqMhz))
            }
            EditableAprsRow("Set B Freq", "144.3900") {
                editField = "aprs_freq"; editValue = "144.3900"
            }
            EditableAprsRow("APRS Power", when {
                chB?.txAtMaxPower == true -> "High"
                chB?.txAtMedPower == true -> "Medium"
                else -> "Low"
            }) {
                editField = "aprs_power"; editValue = when {
                    chB?.txAtMaxPower == true -> "0"
                    chB?.txAtMedPower == true -> "1"
                    else -> "2"
                }
            }
        }

    }

    // Edit dialog
    if (editField != null) {
        AprsEditDialog(
            field     = editField!!,
            value     = editValue,
            onValueChange = { editValue = it },
            onSave    = { field, value ->
                when (field) {
                    "callsign" -> vm.updateBssSettings { it.copy(aprsCallsign = value.uppercase().take(6)) }
                    "ssid"     -> vm.updateBssSettings { s -> value.toIntOrNull()?.coerceIn(0, 15)?.let { s.copy(aprsSsid = it) } ?: s }
                    "symbol"   -> vm.updateBssSettings { it.copy(aprsSymbol = value.take(2)) }
                    "idinfo"   -> vm.updateBssSettings { it.copy(pttReleaseIdInfo = value.take(12)) }
                    "beacon"   -> vm.updateBssSettings { it.copy(beaconMessage = value.take(18)) }
                    "interval" -> vm.updateBssSettings { s -> value.toIntOrNull()?.coerceIn(10, 9999)?.let { s.copy(locationShareIntervalSec = it) } ?: s }
                    "ttl"      -> vm.updateBssSettings { s -> value.toIntOrNull()?.coerceIn(0, 15)?.let { s.copy(timeToLive = it) } ?: s }
                    "maxfwd"   -> vm.updateBssSettings { s -> value.toIntOrNull()?.coerceIn(0, 15)?.let { s.copy(maxFwdTimes = it) } ?: s }
                    "aprs_freq" -> value.toDoubleOrNull()?.let { mhz -> vm.setChannelBFreq((mhz * 1_000_000).toLong()) }
                    "aprs_power" -> value.toIntOrNull()?.let { vm.setChannelBPower(it) }
                }
                editField = null
            },
            onDismiss = { editField = null },
        )
    }
}

@Composable
private fun AprsGroup(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard),
    ) {
        Text(
            text     = title.uppercase(),
            style    = MaterialTheme.typography.labelLarge,
            color    = AprsColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        HorizontalDivider(color = Outline)
        content()
    }
}

@Composable
private fun AprsInfoRow(label: String, value: String) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
    }
    HorizontalDivider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun EditableAprsRow(label: String, value: String, onTap: () -> Unit) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = AprsColor)
            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = OnSurfaceMuted, modifier = Modifier.size(14.dp))
        }
    }
    HorizontalDivider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 16.dp))
}

// ---------------------------------------------------------------------------
// APRS presets
// ---------------------------------------------------------------------------

private val SSID_PRESETS = listOf(
    "0"  to "Home/WX",
    "1"  to "Digi",
    "5"  to "Phone",
    "7"  to "HT",
    "8"  to "Boat",
    "9"  to "Mobile",
    "10" to "iGate",
    "13" to "WX Stn",
    "14" to "Trucker",
    "15" to "HF",
)

private val SYMBOL_PRESETS = listOf(
    "/>" to "Car",
    "/R" to "RV",
    "/k" to "Truck",
    "/-" to "House",
    "/y" to "Yagi",
    "/[" to "Jogger",
    "/s" to "Boat",
    "/O" to "Balloon",
    "/f" to "Fire",
    "/u" to "Bus",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AprsEditDialog(
    field: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, maxLen, kbType) = when (field) {
        "callsign" -> Triple("Callsign", 6, KeyboardType.Text)
        "ssid"     -> Triple("SSID (0-15)", 2, KeyboardType.Number)
        "symbol"   -> Triple("APRS Symbol", 2, KeyboardType.Text)
        "idinfo"   -> Triple("ID Info", 12, KeyboardType.Text)
        "beacon"   -> Triple("Beacon Message", 18, KeyboardType.Text)
        "interval" -> Triple("Interval (seconds)", 4, KeyboardType.Number)
        "ttl"      -> Triple("Time to Live (0-15)", 2, KeyboardType.Number)
        "maxfwd"     -> Triple("Max Forwards (0-15)", 2, KeyboardType.Number)
        "aprs_freq"  -> Triple("APRS Frequency (MHz)", 10, KeyboardType.Decimal)
        "aprs_power" -> Triple("APRS TX Power", 1, KeyboardType.Number)
        else         -> Triple("Edit", 20, KeyboardType.Text)
    }
    val presets = when (field) {
        "ssid"       -> SSID_PRESETS
        "symbol"     -> SYMBOL_PRESETS
        "aprs_power" -> listOf("0" to "High", "1" to "Medium", "2" to "Low")
        else         -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { v ->
                        val transformed = if (field == "callsign") v.uppercase() else v
                        if (transformed.length <= maxLen) onValueChange(transformed)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = kbType,
                        capitalization = if (field == "callsign") KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AprsColor,
                        cursorColor = AprsColor,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (presets != null) {
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        presets.forEach { (preset, label) ->
                            val isSelected = value == preset
                            FilterChip(
                                selected = isSelected,
                                onClick  = { onValueChange(preset) },
                                label    = {
                                    Text(
                                        if (field == "ssid") "$preset $label" else "$label $preset",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AprsColor.copy(alpha = 0.2f),
                                    selectedLabelColor     = AprsColor,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled             = true,
                                    selected            = isSelected,
                                    selectedBorderColor = AprsColor,
                                    borderColor         = Outline,
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(field, value) }) {
                Text("Save", color = AprsColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked         = checked,
            onCheckedChange = onChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor = AprsColor,
                checkedTrackColor = AprsColor.copy(0.4f),
            ),
        )
    }
    HorizontalDivider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 16.dp))
}
