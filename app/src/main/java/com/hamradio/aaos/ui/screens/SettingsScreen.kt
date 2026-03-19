package com.hamradio.aaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hamradio.aaos.radio.protocol.RadioSettings
import com.hamradio.aaos.ui.theme.Accent
import com.hamradio.aaos.ui.theme.Background
import com.hamradio.aaos.ui.theme.OnSurfaceMuted
import com.hamradio.aaos.ui.theme.Outline
import com.hamradio.aaos.ui.theme.SurfaceCard
import com.hamradio.aaos.ui.theme.SurfaceElevated
import com.hamradio.aaos.vm.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel, onOpenDebug: () -> Unit = {}) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rxRoute = 2
    val txRoute = 2
    val autoSwitch = false
    val showDebug = false
    val isMock   = vm.isMockMode
    var showScanConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        val s = settings
        if (s == null) {
            Text(
                "Waiting for radio…",
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurfaceMuted,
            )
        } else {

        SettingsGroup("RF") {
            SettingRow("Squelch") {
                LevelSlider(
                    value    = s.squelchLevel,
                    max      = 9,
                    onValue  = { v -> vm.updateSettings { it.copy(squelchLevel = v) } },
                )
            }
            SettingRow("Scan") {
                Switch(
                    checked         = s.scan,
                    onCheckedChange = { newVal ->
                        if (newVal) vm.setScan(true)
                        else showScanConfirm = true
                    },
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
            CyclableSettingRow(
                label = "Dual Watch",
                value = when (s.doubleChannel) { 0 -> "Off"; 1 -> "Single"; else -> "Dual" },
                onCycle = { vm.updateSettings { it.copy(doubleChannel = (it.doubleChannel + 1) % 3) } },
            )
            SettingRow("Tail Elimination") {
                Switch(
                    checked         = s.tailElim,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(tailElim = v) } },
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
            SettingRow("PTT Lock") {
                Switch(
                    checked         = s.pttLock,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(pttLock = v) } },
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
        }

        SettingsGroup("Power") {
            SettingRow("Auto Power On") {
                Switch(
                    checked         = s.autoPowerOn,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(autoPowerOn = v) } },
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
            SettingRow("Power Saving") {
                Switch(
                    checked         = s.powerSavingMode,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(powerSavingMode = v) } },
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
            CyclableSettingRow(
                label = "Auto Power Off",
                value = if (s.autoPowerOff == 0) "Disabled" else "${s.autoPowerOff * 10} min",
                onCycle = { vm.updateSettings { it.copy(autoPowerOff = (it.autoPowerOff + 1) % 7) } },
            )
        }

        SettingsGroup("GPS") {
            CyclableSettingRow(
                label = "Positioning System",
                value = when (s.positioningSystem) { 0 -> "GPS"; 1 -> "BDS"; else -> "GPS+BDS" },
                onCycle = { vm.updateSettings { it.copy(positioningSystem = (it.positioningSystem + 1) % 3) } },
            )
            SettingRow("Imperial Units") {
                Switch(
                    checked         = s.imperialUnit,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(imperialUnit = v) } },
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
        }

        SettingsGroup("Audio Routing") {
            CyclableSettingRow(
                label = "RX Speaker",
                value = when (rxRoute) { 0 -> "Radio"; 1 -> "Vehicle"; else -> "Both" },
                onCycle = { vm.setRxAudioRoute((rxRoute + 1) % 3) },
            )
            CyclableSettingRow(
                label = "TX Microphone",
                value = when (txRoute) { 0 -> "Radio"; 1 -> "Vehicle"; else -> "Auto" },
                onCycle = { vm.setTxMicRoute((txRoute + 1) % 3) },
            )
        }

        SettingsGroup("Behavior") {
            SettingRow("Auto-Switch on RX") {
                Switch(
                    checked         = autoSwitch,
                    onCheckedChange = { vm.setAutoSwitchOnRx(it) },
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
        }

        SettingsGroup("Audio Levels") {
            CyclableSettingRow(
                label = "Local Speaker",
                value = when (s.localSpeaker) { 0 -> "Off"; 1 -> "Low"; 2 -> "Medium"; else -> "High" },
                onCycle = { vm.updateSettings { it.copy(localSpeaker = (it.localSpeaker + 1) % 4) } },
            )
            SettingRow("Mic Gain") {
                LevelSlider(value = s.micGain, max = 7, onValue = { v -> vm.updateSettings { it.copy(micGain = v) } })
            }
            SettingRow("BT Mic Gain") {
                LevelSlider(value = s.btMicGain, max = 7, onValue = { v -> vm.updateSettings { it.copy(btMicGain = v) } })
            }
        }
        } // else (settings != null)

        SettingsGroup("Developer") {
            SettingRow("Mock Radio") {
                Switch(
                    checked         = isMock,
                    onCheckedChange = { vm.toggleMockMode() },
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
            CyclableSettingRow(
                label = "Debug Console",
                value = "Open",
                onCycle = onOpenDebug,
            )
        }
    }

    if (showScanConfirm) {
        AlertDialog(
            onDismissRequest = { showScanConfirm = false },
            title = { Text("Stop Scan?") },
            text  = { Text("The radio will stop on the current channel.") },
            confirmButton = {
                TextButton(onClick = { vm.setScan(false); showScanConfirm = false }) {
                    Text("Stop", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showScanConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard),
    ) {
        Text(
            text     = title.uppercase(),
            style    = MaterialTheme.typography.labelLarge,
            color    = Accent,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        HorizontalDivider(color = Outline)
        content()
    }
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        control()
    }
    HorizontalDivider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 16.dp))
}

/** Tap-to-cycle setting row with a chevron indicating interactivity. */
@Composable
private fun CyclableSettingRow(label: String, value: String, onCycle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCycle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text  = value,
                style = MaterialTheme.typography.bodyMedium,
                color = Accent,
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Cycle",
                tint = OnSurfaceMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    HorizontalDivider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun LevelSlider(value: Int, max: Int, onValue: (Int) -> Unit) {
    var pos by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Slider(
            value         = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { onValue(pos.toInt()) },
            valueRange    = 0f..max.toFloat(),
            steps         = max - 1,
            modifier      = Modifier.width(140.dp),
            colors        = SliderDefaults.colors(
                thumbColor         = Accent,
                activeTrackColor   = Accent,
                inactiveTrackColor = Outline,
            ),
        )
        Text(
            text  = pos.toInt().toString(),
            style = MaterialTheme.typography.labelLarge,
            color = Accent,
        )
    }
}
