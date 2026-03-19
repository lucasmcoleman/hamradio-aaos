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
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val bss by vm.bssSettings.collectAsStateWithLifecycle()

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
            AprsInfoRow("Callsign",      b.aprsCallsign.ifBlank { "—" })
            AprsInfoRow("SSID",          "-${b.aprsSsid}")
            AprsInfoRow("Symbol",        b.aprsSymbol.ifBlank { "—" })
            AprsInfoRow("ID Info",       b.pttReleaseIdInfo.ifBlank { "—" })
        }

        // Beacon
        AprsGroup("Beacon") {
            AprsInfoRow("Message",       b.beaconMessage.ifBlank { "(none)" })
            AprsInfoRow("Interval",      "${b.locationShareIntervalSec}s")
            AprsInfoRow("TTL",           b.timeToLive.toString())
            AprsInfoRow("Max Forwards",  b.maxFwdTimes.toString())
            ToggleRow("Share Location",  b.shouldShareLocation, {})
            ToggleRow("Send PTT Location", b.pttReleaseSendLocation, {})
            ToggleRow("Send ID on PTT",  b.pttReleaseSendIdInfo, {})
        }

        // Power / position
        AprsGroup("Options") {
            ToggleRow("Send Power/Voltage", b.sendPwrVoltage, {})
            ToggleRow("Allow Position Check", b.allowPositionCheck, {})
        }
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
        Divider(color = Outline)
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
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Accent)
    }
    Divider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 16.dp))
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
    Divider(color = Outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 16.dp))
}
