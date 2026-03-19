package com.hamradio.aaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hamradio.aaos.radio.protocol.ModulationType
import com.hamradio.aaos.radio.protocol.RfChannel
import com.hamradio.aaos.ui.components.ChannelCard
import com.hamradio.aaos.ui.theme.Accent
import com.hamradio.aaos.ui.theme.Background
import com.hamradio.aaos.ui.theme.OnSurfaceMuted
import com.hamradio.aaos.ui.theme.Outline
import com.hamradio.aaos.vm.MainViewModel

private enum class ChannelSlot { A, B }

@Composable
fun ChannelsScreen(vm: MainViewModel) {
    val channels   by vm.channels.collectAsStateWithLifecycle()
    val settings   by vm.settings.collectAsStateWithLifecycle()
    val htStatus   by vm.htStatus.collectAsStateWithLifecycle()

    var query      by remember { mutableStateOf("") }
    var targetSlot by remember { mutableStateOf(ChannelSlot.A) }

    val filtered = remember(channels, query) {
        if (query.isBlank()) channels
        else channels.filter {
            it.name.contains(query, ignoreCase = true) ||
            "%.4f".format(it.txFreqMhz).contains(query) ||
            "%.4f".format(it.rxFreqMhz).contains(query)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header row
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = "Channels  (${channels.size})",
                style = MaterialTheme.typography.headlineMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Slot selector
                Text("Set:", style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
                SlotChip("A", targetSlot == ChannelSlot.A) { targetSlot = ChannelSlot.A }
                SlotChip("B", targetSlot == ChannelSlot.B) { targetSlot = ChannelSlot.B }
                Spacer(Modifier.width(8.dp))
                // Refresh
                IconButton(onClick = { vm.refreshChannels() }) {
                    Icon(Icons.Default.Refresh, "Refresh channels", tint = Accent)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Search bar
        OutlinedTextField(
            value         = query,
            onValueChange = { query = it },
            placeholder   = { Text("Search name or frequency…", color = OnSurfaceMuted) },
            leadingIcon   = { Icon(Icons.Default.Search, null, tint = OnSurfaceMuted) },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            shape         = RoundedCornerShape(12.dp),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Accent,
                unfocusedBorderColor = Outline,
                cursorColor          = Accent,
            ),
        )

        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Text(
                text      = if (query.isBlank()) "No channels loaded" else "No channels match "$query"",
                style     = MaterialTheme.typography.bodyLarge,
                color     = OnSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth().padding(top = 48.dp),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding      = PaddingValues(bottom = 16.dp),
            ) {
                items(filtered, key = { it.channelId }) { ch ->
                    ChannelCard(
                        channel    = ch,
                        isActiveA  = ch.channelId == settings?.channelA,
                        isActiveB  = ch.channelId == settings?.channelB,
                        isRx       = htStatus.isSquelchOpen && ch.channelId == settings?.channelA,
                        isTx       = htStatus.isInTx && ch.channelId == settings?.channelA,
                        onClick    = {
                            when (targetSlot) {
                                ChannelSlot.A -> vm.selectChannelA(ch.channelId)
                                ChannelSlot.B -> vm.selectChannelB(ch.channelId)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick  = onClick,
        label    = { Text(label, style = MaterialTheme.typography.labelLarge) },
        colors   = FilterChipDefaults.filterChipColors(
            selectedContainerColor  = Accent.copy(alpha = 0.2f),
            selectedLabelColor      = Accent,
            containerColor          = Background,
            labelColor              = OnSurfaceMuted,
        ),
        border   = FilterChipDefaults.filterChipBorder(
            enabled               = true,
            selected              = selected,
            selectedBorderColor   = Accent,
            borderColor           = Outline,
        ),
    )
}
