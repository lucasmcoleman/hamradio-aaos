package com.hamradio.aaos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hamradio.aaos.ui.theme.TxRed
import com.hamradio.aaos.ui.theme.TxRedDim

/**
 * Push-to-talk control shared by the home and driving screens. Hold to transmit, or tap on/off in
 * toggle mode. Release always runs: on finger up, on a cancelled gesture, and when the button
 * leaves the screen.
 */
@Composable
fun PttButton(
    isTransmitting: Boolean,
    toggleMode: Boolean = false,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onPttToggle: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().height(52.dp),
    labelStyle: TextStyle = MaterialTheme.typography.titleMedium,
    /** Shown while transmitting. Mock mode passes a loopback label so it can't pass for RF. */
    txLabel: String = "TX",
) {
    // pointerInput outlives recompositions, so read the latest callbacks through these.
    val down   by rememberUpdatedState(onPttDown)
    val up     by rememberUpdatedState(onPttUp)
    val toggle by rememberUpdatedState(onPttToggle)
    // Leaving the screen (tab change, sheet, activity teardown) must never leave the radio keyed.
    DisposableEffect(Unit) { onDispose { up() } }

    val bgColor = if (isTransmitting) TxRed else TxRedDim
    val textColor = if (isTransmitting) Color.White else TxRed

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .pointerInput(toggleMode) {
                if (toggleMode) {
                    // Tap on / tap off
                    detectTapGestures(onTap = { toggle() })
                } else {
                    // Momentary: hold to transmit
                    detectTapGestures(
                        onPress = {
                            down()
                            try {
                                tryAwaitRelease()
                            } finally {
                                up()      // also runs if the gesture is cancelled mid-press
                            }
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = if (isTransmitting) txLabel else "PTT",
            style = labelStyle,
            textAlign = TextAlign.Center,
            color = textColor,
        )
    }
}
