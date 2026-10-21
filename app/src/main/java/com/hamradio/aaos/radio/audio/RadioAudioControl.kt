package com.hamradio.aaos.radio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.flow.StateFlow

/** Session boundary used by the controller and hardware-free lifecycle tests. */
interface RadioAudioControl {
    val isConnected: StateFlow<Boolean>
    val isTransmitting: StateFlow<Boolean>
    suspend fun connect()
    suspend fun disconnect()
    suspend fun close() { disconnect() }
    suspend fun startTransmit(): Boolean
    suspend fun stopTransmit()
}

internal interface AudioFocusControl {
    fun request(onLoss: () -> Unit): Boolean
    fun abandon()
}

internal class AndroidRadioAudioFocus(context: Context) : AudioFocusControl {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null
    override fun request(onLoss: () -> Unit): Boolean {
        abandon()
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { if (it < 0) onLoss() }.build()
        request = next
        return manager.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    override fun abandon() {
        request?.let { manager.abandonAudioFocusRequest(it) }
        request = null
    }
}
