package com.hamradio.aaos.radio.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

private const val TAG = "RadioAudio"

// The radio's audio RFCOMM service — GenericAudio UUID
private val GENERIC_AUDIO_UUID = UUID.fromString("00001203-0000-1000-8000-00805F9B34FB")
// Fallback: standard SPP UUID (some radios use SPP for audio on a different channel)
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * Manages the second RFCOMM connection to the radio's audio channel.
 *
 * Audio framing uses HDLC-like 0x7E delimiters with 0x7D escape sequences.
 * Voice is SBC-encoded at 32kHz mono, 16 blocks, 8 subbands, bitpool 18.
 *
 * To transmit: capture microphone → encode PCM to SBC → frame → write to audio stream.
 * The radio automatically keys up when it receives audio frames and stops when
 * it receives the end-audio marker.
 */
@SuppressLint("MissingPermission")
class RadioAudioChannel(private val context: Context, private val deviceAddress: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var transmitJob: Job? = null

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // End-of-audio marker — tells the radio to stop transmitting
    private val END_AUDIO = byteArrayOf(
        0x7E, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7E
    )

    /**
     * Connect to the radio's audio RFCOMM channel.
     * This is separate from the data RFCOMM connection.
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return@withContext
            adapter.cancelDiscovery()

            val device = adapter.getRemoteDevice(deviceAddress)
            Log.i(TAG, "Connecting audio channel to ${device.name} ($deviceAddress)")

            // Try GenericAudio UUID first, then SPP
            val sock = try {
                device.createRfcommSocketToServiceRecord(GENERIC_AUDIO_UUID)
            } catch (e: IOException) {
                Log.w(TAG, "GenericAudio UUID failed, trying SPP", e)
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            }

            sock.connect()
            socket = sock
            inputStream = sock.inputStream
            outputStream = sock.outputStream
            _isConnected.value = true
            Log.i(TAG, "Audio channel connected")
        } catch (e: Exception) {
            Log.e(TAG, "Audio channel connect failed: ${e.message}")
            closeSocket()
        }
    }

    suspend fun disconnect() {
        stopTransmit()
        closeSocket()
        _isConnected.value = false
    }

    /**
     * Start transmitting — captures microphone audio, encodes to SBC, sends to radio.
     * The radio automatically keys up when it receives audio data.
     */
    @SuppressLint("MissingPermission")
    fun startTransmit() {
        if (_isTransmitting.value || !_isConnected.value) {
            Log.w(TAG, "Cannot start TX: transmitting=${_isTransmitting.value}, connected=${_isConnected.value}")
            return
        }
        _isTransmitting.value = true

        transmitJob = scope.launch {
            var audioRecord: AudioRecord? = null
            try {
                val sampleRate = 32000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val bufSize = maxOf(minBufSize, SbcCodec.PCM_BYTES_PER_FRAME * 4)

                audioRecord = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufSize,
                    )
                } catch (e: SecurityException) {
                    Log.e(TAG, "RECORD_AUDIO permission not granted", e)
                    _isTransmitting.value = false
                    return@launch
                }

                if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    _isTransmitting.value = false
                    return@launch
                }

                audioRecord!!.startRecording()
                Log.i(TAG, "TX: Microphone capture started")

                val pcmBuffer = ShortArray(SbcCodec.SAMPLES_PER_FRAME)

                while (isActive && _isTransmitting.value) {
                    val read = audioRecord!!.read(pcmBuffer, 0, SbcCodec.SAMPLES_PER_FRAME)
                    if (read <= 0) continue

                    // Encode PCM to SBC
                    val sbcFrame = SbcCodec.encode(pcmBuffer) ?: continue

                    // Frame with 0x7E delimiters and escape
                    val escaped = escapeFrame(0x00, sbcFrame)

                    // Write to radio
                    try {
                        outputStream?.write(escaped)
                        outputStream?.flush()
                    } catch (e: IOException) {
                        Log.e(TAG, "Audio write failed", e)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TX error", e)
            } finally {
                // Send end-of-audio marker
                try {
                    outputStream?.write(END_AUDIO)
                    outputStream?.flush()
                    Log.i(TAG, "TX: Sent end-audio marker")
                } catch (_: IOException) {}

                audioRecord?.stop()
                audioRecord?.release()
                _isTransmitting.value = false
                Log.i(TAG, "TX: Stopped")
            }
        }
    }

    /** Stop transmitting — sends end-audio marker and releases microphone. */
    fun stopTransmit() {
        _isTransmitting.value = false
        transmitJob?.cancel()
        transmitJob = null
    }

    // -----------------------------------------------------------------------
    // HDLC-like framing: 0x7E delimiters, 0x7D escape
    // -----------------------------------------------------------------------

    /**
     * Wrap data in 0x7E-delimited frame with HDLC escaping.
     * Format: [0x7E][cmd][escaped data...][0x7E]
     */
    private fun escapeFrame(cmd: Byte, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2 + 4)
        out.write(0x7E)
        out.write(cmd.toInt())
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v == 0x7E || v == 0x7D) {
                out.write(0x7D)
                out.write(v xor 0x20)
            } else {
                out.write(v)
            }
        }
        out.write(0x7E)
        return out.toByteArray()
    }

    private fun closeSocket() {
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        inputStream = null
        outputStream = null
        socket = null
    }
}
