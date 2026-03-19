package com.hamradio.aaos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hamradio.aaos.ui.theme.Accent
import kotlinx.coroutines.delay

@Composable
fun VolumeSlider(
    volume:        Int,
    onVolumeChange: (Int) -> Unit,
    modifier:      Modifier = Modifier,
) {
    var sliderPos by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    var pendingVolume by remember { mutableFloatStateOf(volume.toFloat()) }

    // Throttled volume updates during drag — sends every 200ms
    LaunchedEffect(isDragging, pendingVolume) {
        if (isDragging) {
            delay(200)
            onVolumeChange(pendingVolume.toInt())
        }
    }

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
                text  = sliderPos.toInt().toString(),
                style = MaterialTheme.typography.labelLarge,
                color = Accent,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (sliderPos.toInt() == 0) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeDown,
                contentDescription = null,
                tint   = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Slider(
                value         = sliderPos,
                onValueChange = { sliderPos = it; isDragging = true; pendingVolume = it },
                onValueChangeFinished = { isDragging = false; onVolumeChange(sliderPos.toInt()) },
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
                imageVector  = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint         = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier     = Modifier.size(20.dp),
            )
        }
    }
}
