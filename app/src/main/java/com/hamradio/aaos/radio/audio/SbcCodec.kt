package com.hamradio.aaos.radio.audio

import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal SBC encoder/decoder for Benshi radio audio.
 *
 * Fixed configuration matching the radio's audio channel:
 * - 32 kHz sample rate
 * - Mono
 * - 16 blocks
 * - 8 subbands
 * - Loudness bit allocation
 * - Bitpool 18
 *
 * Reference: A2DP spec, Bluetooth SIG
 */
object SbcCodec {

    // SBC header sync word
    const val SBC_SYNCWORD = 0x9C.toByte()

    // Fixed config for Benshi radios
    const val FREQUENCY = 2        // 32 kHz (0=16k, 1=32k... wait: 0=16k, 1=32k, 2=44.1k, 3=48k)
    // Actually: 0=16kHz, 1=32kHz, 2=44.1kHz, 3=48kHz
    const val FREQ_32K = 1
    const val BLOCKS = 16
    const val MODE_MONO = 0
    const val ALLOCATION_LOUDNESS = 1
    const val SUBBANDS = 8
    const val BITPOOL = 18

    // Derived constants
    const val SAMPLES_PER_FRAME = BLOCKS * SUBBANDS  // 128 samples
    const val PCM_BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2  // 256 bytes (16-bit mono)

    // Precomputed analysis filter coefficients for 8 subbands
    private val analysisFilter: Array<DoubleArray> = Array(8) { k ->
        DoubleArray(80) { i ->
            val proto = protoCoeffs8[i]
            proto * cos((k + 0.5) * (i - 3.5) * PI / 8.0)
        }
    }

    // SBC prototype filter coefficients for 8 subbands (from Bluetooth SIG spec)
    private val protoCoeffs8 = doubleArrayOf(
        0.00000000E+00, 1.56575398E-04, 3.43256425E-04, 5.54620202E-04,
        8.23919506E-04, 1.13992507E-03, 1.47640169E-03, 1.78371725E-03,
        2.01182542E-03, 2.10371989E-03, 1.99454554E-03, 1.61656283E-03,
        9.02154502E-04, -1.78805361E-04, -1.64973098E-03, -3.49717454E-03,
        5.65949473E-03, 8.02941163E-03, 1.04584443E-02, 1.27472335E-02,
        1.46525263E-02, 1.59045603E-02, 1.62208471E-02, 1.53184106E-02,
        1.29371806E-02, 8.85757540E-03, 2.92408442E-03, -4.91578024E-03,
        -1.46404076E-02, -2.61098752E-02, -3.90751381E-02, -5.31873032E-02,
        6.79989431E-02, 8.29847578E-02, 9.75753918E-02, 1.11196689E-01,
        1.23264548E-01, 1.33264415E-01, 1.40753505E-01, 1.45389847E-01,
        1.46955068E-01, 1.45389847E-01, 1.40753505E-01, 1.33264415E-01,
        1.23264548E-01, 1.11196689E-01, 9.75753918E-02, 8.29847578E-02,
        -6.79989431E-02, -5.31873032E-02, -3.90751381E-02, -2.61098752E-02,
        -1.46404076E-02, -4.91578024E-03, 2.92408442E-03, 8.85757540E-03,
        1.29371806E-02, 1.53184106E-02, 1.62208471E-02, 1.59045603E-02,
        1.46525263E-02, 1.27472335E-02, 1.04584443E-02, 8.02941163E-03,
        -5.65949473E-03, -3.49717454E-03, -1.64973098E-03, -1.78805361E-04,
        9.02154502E-04, 1.61656283E-03, 1.99454554E-03, 2.10371989E-03,
        2.01182542E-03, 1.78371725E-03, 1.47640169E-03, 1.13992507E-03,
        8.23919506E-04, 5.54620202E-04, 3.43256425E-04, 1.56575398E-04,
    )

    // Sliding window for analysis filter
    private val analysisBuffer = DoubleArray(80)

    /**
     * Encode a single SBC frame from PCM samples.
     *
     * @param pcm 128 mono 16-bit PCM samples (256 bytes as ShortArray)
     * @return encoded SBC frame bytes, or null on error
     */
    fun encode(pcm: ShortArray): ByteArray? {
        if (pcm.size < SAMPLES_PER_FRAME) return null

        // Analysis: compute subband samples
        val sbSamples = Array(BLOCKS) { IntArray(SUBBANDS) }
        for (blk in 0 until BLOCKS) {
            // Shift analysis buffer
            for (i in 79 downTo SUBBANDS) {
                analysisBuffer[i] = analysisBuffer[i - SUBBANDS]
            }
            for (i in SUBBANDS - 1 downTo 0) {
                analysisBuffer[SUBBANDS - 1 - i] = pcm[blk * SUBBANDS + i].toDouble()
            }

            // Apply analysis filter
            for (sb in 0 until SUBBANDS) {
                var sum = 0.0
                for (i in 0 until 80) {
                    sum += analysisFilter[sb][i] * analysisBuffer[i]
                }
                sbSamples[blk][sb] = (sum * 32768.0).toInt().coerceIn(-32768 * 32768, 32767 * 32768)
            }
        }

        // Compute scale factors
        val scaleFactor = IntArray(SUBBANDS)
        for (sb in 0 until SUBBANDS) {
            var maxVal = 0
            for (blk in 0 until BLOCKS) {
                val v = if (sbSamples[blk][sb] < 0) -sbSamples[blk][sb] else sbSamples[blk][sb]
                if (v > maxVal) maxVal = v
            }
            var sf = 0
            while ((maxVal shr (sf + 1)) > 0 && sf < 30) sf++
            scaleFactor[sb] = sf
        }

        // Bit allocation (loudness)
        val bits = loudnessBitAllocation(scaleFactor, BITPOOL)

        // Quantize
        val quantized = Array(BLOCKS) { blk ->
            IntArray(SUBBANDS) { sb ->
                if (bits[sb] > 0) {
                    val levels = (1 shl bits[sb]) - 1
                    val sf = scaleFactor[sb]
                    val sample = sbSamples[blk][sb]
                    val normalized = (sample.toDouble() / (1 shl sf).toDouble() + 1.0) / 2.0
                    (normalized * levels + 0.5).toInt().coerceIn(0, levels)
                } else 0
            }
        }

        // Pack into bitstream
        return packFrame(scaleFactor, bits, quantized)
    }

    /**
     * Decode an SBC frame to PCM samples.
     * @return 128 mono 16-bit PCM samples, or null on error
     */
    // Synthesis filter buffer for decoding
    private val synthesisBuffer = DoubleArray(160)

    // Precomputed synthesis filter (transpose of analysis)
    private val synthesisFilter: Array<DoubleArray> = Array(8) { k ->
        DoubleArray(80) { i ->
            val proto = protoCoeffs8[i]
            proto * cos((k + 0.5) * (i - 3.5) * PI / 8.0) * SUBBANDS.toDouble()
        }
    }

    /**
     * Decode an SBC frame to 128 mono 16-bit PCM samples.
     * @return ShortArray of PCM samples, or null if invalid frame
     */
    fun decode(frame: ByteArray, offset: Int = 0): ShortArray? {
        if (offset >= frame.size) return null
        // Find SBC syncword
        var pos = offset
        while (pos < frame.size && frame[pos] != SBC_SYNCWORD) pos++
        if (pos >= frame.size) return null

        val reader = ByteArrayBitReader(frame, pos)

        // Header: syncword(8) + frequency(2) + blocks(2) + mode(2) + alloc(1) + subbands(1) + bitpool(8)
        val sync = reader.read(8)
        if (sync != 0x9C) return null
        val freq = reader.read(2)
        val blocksCode = reader.read(2)
        val mode = reader.read(2)
        val alloc = reader.read(1)
        val subbandsCode = reader.read(1)
        val bitpool = reader.read(8)

        val nBlocks = when (blocksCode) { 0 -> 4; 1 -> 8; 2 -> 12; else -> 16 }
        val nSubbands = if (subbandsCode == 0) 4 else 8

        // Skip CRC
        reader.read(8)

        // Scale factors
        val scaleFactor = IntArray(nSubbands) { reader.read(4) }

        // Bit allocation
        val bits = loudnessBitAllocation(scaleFactor, bitpool)

        // Dequantize
        val sbSamples = Array(nBlocks) { blk ->
            DoubleArray(nSubbands) { sb ->
                if (bits[sb] > 0) {
                    val raw = reader.read(bits[sb])
                    val levels = (1 shl bits[sb]) - 1
                    val normalized = (raw.toDouble() / levels) * 2.0 - 1.0
                    normalized * (1 shl scaleFactor[sb]).toDouble()
                } else 0.0
            }
        }

        // Synthesis filter bank → PCM
        val pcm = ShortArray(nBlocks * nSubbands)
        for (blk in 0 until nBlocks) {
            // Shift synthesis buffer
            for (i in 159 downTo nSubbands) {
                synthesisBuffer[i] = synthesisBuffer[i - nSubbands]
            }
            // Matrixing: multiply subband samples by synthesis filter
            for (i in 0 until nSubbands) {
                var sum = 0.0
                for (sb in 0 until nSubbands) {
                    sum += synthesisFilter[sb][i] * sbSamples[blk][sb]
                }
                synthesisBuffer[i] = sum
            }
            // Windowing + output
            for (i in 0 until nSubbands) {
                var sum = 0.0
                for (j in 0 until 10) {
                    val idx = j * nSubbands * 2 + i
                    if (idx < synthesisBuffer.size && idx < protoCoeffs8.size) {
                        sum += synthesisBuffer[idx] * protoCoeffs8[idx]
                    }
                }
                val sample = (sum / 32768.0).coerceIn(-1.0, 1.0)
                pcm[blk * nSubbands + i] = (sample * 32767).toInt().toShort()
            }
        }

        return pcm
    }

    private class ByteArrayBitReader(private val data: ByteArray, startOffset: Int) {
        var bitPos: Int = startOffset * 8

        fun read(numBits: Int): Int {
            var result = 0
            for (i in 0 until numBits) {
                val byteIdx = bitPos / 8
                val bitIdx = 7 - (bitPos % 8)
                if (byteIdx < data.size) {
                    result = (result shl 1) or ((data[byteIdx].toInt() shr bitIdx) and 1)
                } else {
                    result = result shl 1
                }
                bitPos++
            }
            return result
        }
    }

    private fun loudnessBitAllocation(sf: IntArray, bitpool: Int): IntArray {
        val bits = IntArray(SUBBANDS)
        // Loudness offset table for 8 subbands, mono
        val offset = intArrayOf(-2, 0, 0, 0, 0, 0, 0, 1)

        val loudness = IntArray(SUBBANDS) { max(0, sf[it] / 2 + offset[it]) }

        var remaining = bitpool
        var maxLoudness = loudness.max()

        // Iterative bit allocation
        var bitSlice = maxLoudness + 1
        while (remaining > 0 && bitSlice > 0) {
            bitSlice--
            for (sb in 0 until SUBBANDS) {
                if (remaining <= 0) break
                if (loudness[sb] >= bitSlice && bits[sb] < 16) {
                    if (bits[sb] == 0) {
                        bits[sb] = 2
                        remaining -= 2
                    } else {
                        bits[sb]++
                        remaining--
                    }
                }
            }
        }

        // If remaining bits, distribute evenly
        while (remaining > 0) {
            for (sb in 0 until SUBBANDS) {
                if (remaining <= 0) break
                if (bits[sb] < 16) {
                    bits[sb]++
                    remaining--
                }
            }
        }

        return bits
    }

    private fun packFrame(sf: IntArray, bits: IntArray, quantized: Array<IntArray>): ByteArray {
        val out = ByteArrayBitWriter()

        // Header
        out.write(0x9C, 8)                    // syncword
        out.write(FREQ_32K, 2)                // sampling frequency
        out.write(3, 2)                        // blocks (3 = 16 blocks)
        out.write(MODE_MONO, 2)                // channel mode
        out.write(ALLOCATION_LOUDNESS, 1)      // allocation method
        out.write(1, 1)                        // subbands (1 = 8 subbands)
        out.write(BITPOOL, 8)                  // bitpool

        // CRC (placeholder — compute later)
        val crcPos = out.bitPos
        out.write(0, 8)

        // Scale factors (4 bits each for 8 subbands)
        for (sb in 0 until SUBBANDS) {
            out.write(sf[sb] and 0x0F, 4)
        }

        // Quantized samples
        for (blk in 0 until BLOCKS) {
            for (sb in 0 until SUBBANDS) {
                if (bits[sb] > 0) {
                    out.write(quantized[blk][sb], bits[sb])
                }
            }
        }

        // Pad to byte boundary
        out.padToByte()

        val result = out.toByteArray()

        // Compute CRC over bytes 1..3 + scale factors
        result[3] = computeCrc(result)

        return result
    }

    private fun computeCrc(frame: ByteArray): Byte {
        var crc = 0x0F
        // CRC covers bytes at positions 1, 2 (header after syncword) and scale factor data
        val crcData = byteArrayOf(frame[1], frame[2]) +
            frame.copyOfRange(4, 4 + SUBBANDS / 2) // 4 bits × 8 subbands = 32 bits = 4 bytes
        for (b in crcData) {
            for (bit in 7 downTo 0) {
                val flag = ((crc xor ((b.toInt() shr bit) and 1)) and 1) != 0
                crc = crc shr 1
                if (flag) crc = crc xor 0x1C
            }
        }
        return crc.toByte()
    }

    private class ByteArrayBitWriter {
        private val data = ByteArray(512)
        var bitPos = 0

        fun write(value: Int, numBits: Int) {
            for (i in numBits - 1 downTo 0) {
                val bit = (value shr i) and 1
                val byteIdx = bitPos / 8
                val bitIdx = 7 - (bitPos % 8)
                if (bit == 1) data[byteIdx] = (data[byteIdx].toInt() or (1 shl bitIdx)).toByte()
                bitPos++
            }
        }

        fun padToByte() {
            if (bitPos % 8 != 0) bitPos += (8 - bitPos % 8)
        }

        fun toByteArray(): ByteArray = data.copyOf((bitPos + 7) / 8)
    }
}
