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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val isMock   = vm.isMockMode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        if (settings == null) {
            Text(
                "Waiting for radio…",
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurfaceMuted,
            )
            return@Column
        }

        val s = settings!!

        SettingsGroup("RF") {
            SettingRow("Squelch") {
                LevelSlider(
                    value    = s.squelchLevel,
                    max      = 9,
                    onValue  = { /* write_settings TODO: individual field write */ },
                )
            }
            SettingRow("Mic Gain") {
                LevelSlider(value = s.micGain, max = 7, onValue = {})
            }
            SettingRow("Scan") {
                Switch(
                    checked         = s.scan,
                    onCheckedChange = { vm.setScan(it) },
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
            SettingRow("Dual Watch") {
                Text(
                    text  = when (s.doubleChannel) { 0 -> "Off"; 1 -> "Dual"; else -> "Triple" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Accent,
                )
            }
            SettingRow("Tail Elimination") {
                Switch(
                    checked         = s.tailElim,
                    onCheckedChange = {},
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
            SettingRow("PTT Lock") {
                Switch(
                    checked         = s.pttLock,
                    onCheckedChange = {},
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
        }

        SettingsGroup("Power") {
            SettingRow("Auto Power On") {
                Switch(
                    checked         = s.autoPowerOn,
                    onCheckedChange = {},
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
            SettingRow("Power Saving") {
                Switch(
                    checked         = s.powerSavingMode,
                    onCheckedChange = {},
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
            SettingRow("Auto Power Off") {
                Text(
                    text  = if (s.autoPowerOff == 0) "Disabled" else "${s.autoPowerOff * 10} min",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Accent,
                )
            }
        }

        SettingsGroup("GPS") {
            SettingRow("Positioning System") {
                Text(
                    text  = when (s.positioningSystem) { 0 -> "GPS"; 1 -> "BDS"; else -> "GPS+BDS" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Accent,
                )
            }
            SettingRow("Imperial Units") {
                Switch(
                    checked         = s.imperialUnit,
                    onCheckedChange = {},
                    colors          = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(0.4f)),
                )
            }
        }

        SettingsGroup("Audio") {
            SettingRow("Local Speaker") {
                Text(
                    text  = when (s.localSpeaker) { 0 -> "Off"; 1 -> "Low"; 2 -> "Medium"; else -> "High" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Accent,
                )
            }
            SettingRow("BT Mic Gain") {
                LevelSlider(value = s.btMicGain, max = 7, onValue = {})
            }
        }

        if (isMock) {
            SettingsGroup("Debug") {
                SettingRow("Mock Radio Active") {
                    Text("ON", style = MaterialTheme.typography.bodyMedium, color = Accent)
                }
            }
        }
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
        Divider(color = Outline)
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
    Divider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 16.dp))
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
