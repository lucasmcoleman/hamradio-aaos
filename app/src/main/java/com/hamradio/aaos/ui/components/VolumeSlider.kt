package com.hamradio.aaos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hamradio.aaos.ui.theme.Accent

@Composable
fun VolumeSlider(
    volume:        Int,
    onVolumeChange: (Int) -> Unit,
    modifier:      Modifier = Modifier,
) {
    var sliderPos by remember(volume) { mutableFloatStateOf(volume.toFloat()) }

    Column(modifier = modifier) {
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = "VOL",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text  = volume.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = Accent,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (volume == 0) Icons.Default.VolumeMute else Icons.Default.VolumeDown,
                contentDescription = null,
                tint   = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Slider(
                value         = sliderPos,
                onValueChange = { sliderPos = it },
                onValueChangeFinished = { onVolumeChange(sliderPos.toInt()) },
                valueRange    = 0f..15f,
                steps         = 14,
                modifier      = Modifier.weight(1f),
                colors        = SliderDefaults.colors(
                    thumbColor             = Accent,
                    activeTrackColor       = Accent,
                    inactiveTrackColor     = MaterialTheme.colorScheme.outline,
                ),
            )
            Icon(
                imageVector  = Icons.Default.VolumeUp,
                contentDescription = null,
                tint         = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier     = Modifier.size(20.dp),
            )
        }
    }
}
