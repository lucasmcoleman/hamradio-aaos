/*
 * Copyright 2026 Ylian Saint-Hilaire
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Mono/eight-subband Kotlin adaptation of HTCommander src/lib/sbc,
 * source commit 09dfa3e. See THIRD_PARTY_SBC.md for provenance and modifications.
 */
package com.hamradio.aaos.radio.audio

/** 32 kHz mono SBC, 16 blocks/eight subbands; loudness TX18, loudness or SNR RX. */
class SbcCodecSession {
    companion object {
        const val SBC_SYNCWORD = 0x9C.toByte()
        const val FREQ_32K = 1
        const val BLOCKS = 16
        const val MODE_MONO = 0
        const val ALLOCATION_LOUDNESS = 0
        const val SUBBANDS = 8
        const val BITPOOL = 18
        const val SAMPLES_PER_FRAME = BLOCKS * SUBBANDS
        const val PCM_BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2
    }

    private var encoderIndex = 0
    private val analysis = Array(2) { Array(8) { IntArray(5) } }
    private val analysisPrevious = LongArray(4)
    private var decoderIndex = 0
    private val synthesis = Array(2) { Array(8) { IntArray(10) } }

    /** Begin a new PCM stream; do not reset between contiguous frames. */
    @Synchronized
    fun resetEncoder() {
        encoderIndex = 0
        analysis.forEach { half -> half.forEach { it.fill(0) } }
        analysisPrevious.fill(0)
    }

    /** Begin a new SBC stream; invalid frames never mutate synthesis state. */
    @Synchronized
    fun resetDecoder() {
        decoderIndex = 0
        synthesis.forEach { half -> half.forEach { it.fill(0) } }
    }

    private fun saturate(value: Long): Int = value.coerceIn(-32768L, 32767L).toInt()

    @Synchronized
    fun encode(pcm: ShortArray): ByteArray? {
        if (pcm.size < SAMPLES_PER_FRAME) return null
        val samples = Array(BLOCKS) { analyze(pcm, it * SUBBANDS) }
        val sf = IntArray(SUBBANDS) { sb ->
            var magnitude = 0
            for (block in samples) magnitude = magnitude or kotlin.math.abs(block[sb])
            if (magnitude == 0) 0 else 31 - Integer.numberOfLeadingZeros(magnitude)
        }
        val bits = bitAllocation(sf, BITPOOL)
        val quantized = Array(BLOCKS) { block ->
            IntArray(SUBBANDS) { sb ->
                val levels = (1 shl bits[sb]) - 1
                ((((samples[block][sb].toLong() * levels) shr (sf[sb] + 1)) + levels) shr 1).toInt()
            }
        }
        return packFrame(sf, bits, quantized)
    }

    private fun analyze(pcm: ShortArray, offset: Int): IntArray {
        val index = encoderIndex shr 1
        val odd = encoderIndex and 1
        val inputIndex = if (index == 0) 0 else 5 - index
        val order = intArrayOf(7, 3, 6, 0, 5, 1, 4, 2)
        for (i in 0..7) analysis[odd][i][inputIndex] = pcm[offset + order[i]].toInt()
        val first = LongArray(8) { i ->
            var sum = 0L
            for (j in 0..4) sum += analysis[odd][i][j].toLong() * SbcTables.analysisWindow[i][index + j]
            sum
        }
        val y = longArrayOf(first[0] + analysisPrevious[0], first[2] + first[3],
            first[4] + first[5], first[6] + first[7], analysisPrevious[1],
            analysisPrevious[2], analysisPrevious[3], first[1])
        analysisPrevious.fill(0)
        for (j in 0..4) {
            analysisPrevious[0] += analysis[odd][0][j].toLong() * SbcTables.analysisWindowB[0][index + j]
            for (i in 1..3) {
                analysisPrevious[i] += analysis[odd][2 * i][j].toLong() * SbcTables.analysisWindowB[2 * i][index + j] -
                    analysis[odd][2 * i + 1][j].toLong() * SbcTables.analysisWindowB[2 * i + 1][index + j]
            }
        }
        val windowed = IntArray(8) { saturate((y[it] + (1L shl 14)) shr 15) }
        encoderIndex = (encoderIndex + 1) % 10
        return IntArray(8) { i ->
            var sum = 0L
            for (j in 0..7) sum += windowed[j].toLong() * SbcTables.cosMatrix[i][j]
            saturate((sum + (1L shl 12)) shr 13)
        }
    }

    @Synchronized
    fun decode(frame: ByteArray, offset: Int = 0): ShortArray? {
        if (offset < 0 || offset >= frame.size) return null
        var pos = offset
        while (pos < frame.size && frame[pos] != SBC_SYNCWORD) pos++
        val length = frameLength(frame, pos) ?: return null
        val reader = ByteArrayBitReader(frame.copyOfRange(pos, pos + length), 4)
        val sf = IntArray(SUBBANDS) { reader.read(4) }
        val bits = bitAllocation(sf, frame[pos + 2].toInt() and 0xFF,
            snr = (frame[pos + 1].toInt() and 2) != 0)
        val scale = 14 - sf.max()
        val pcm = ShortArray(SAMPLES_PER_FRAME)
        for (block in 0 until BLOCKS) {
            val samples = IntArray(SUBBANDS) { sb ->
                if (bits[sb] == 0) 0 else {
                    val raw = reader.read(bits[sb])
                    dequantizeSample(raw, bits[sb], sf[sb], scale)
                }
            }
            synthesize(samples, scale, pcm, block * SUBBANDS)
        }
        return pcm
    }

    // Payload bits are not CRC-protected. Saturate corrupt extreme codewords rather
    // than wrapping a positive reconstructed subband sample into negative Int16.
    internal fun dequantizeSample(raw: Int, bits: Int, sf: Int, scale: Int): Int {
        val sample = ((raw.toLong() shl 1) or 1L) * SbcTables.rangeScale[bits - 1]
        return saturate((sample - (1L shl 28)) shr (28 - (sf + 1 + scale)))
    }

    private fun synthesize(input: IntArray, scale: Int, pcm: ShortArray, offset: Int) {
        val index = if (decoderIndex == 0) 0 else 10 - decoderIndex
        val odd = index and 1
        val c = SbcTables.cos16
        val sums = LongArray(4) { (input[it].toLong() + input[7 - it]) shr 1 }
        val diffs = LongArray(4) { (input[it].toLong() - input[7 - it]) shr 1 }
        val s07 = sums[0]; val s16 = sums[1]; val s25 = sums[2]; val s34 = sums[3]
        val d07 = diffs[0]; val d16 = diffs[1]; val d25 = diffs[2]; val d34 = diffs[3]
        val a = longArrayOf(
            (s07 + s34 - s25 - s16) * c[4],
            d07 * c[5] - d16 * c[1] + d25 * c[7] + d34 * c[3],
            (s07 - s34) * c[6] + (s25 - s16) * c[2],
            d07 * c[7] - d16 * c[5] + d25 * c[3] - d34 * c[1],
        )
        val b = longArrayOf(
            -d07 * c[3] + d16 * c[7] + d25 * c[1] + d34 * c[5],
            (s34 - s07) * c[2] + (s25 - s16) * c[6],
            -d07 * c[1] - d16 * c[3] - d25 * c[5] - d34 * c[7],
            (-s07 - s34 - s25 - s16) shl 13,
        )
        val shift = 12 + scale
        for (i in 0..3) {
            a[i] = (a[i] + (1L shl (shift - 1))) shr shift
            b[i] = (b[i] + (1L shl (shift - 1))) shr shift
        }
        val even = longArrayOf(a[0], a[1], a[2], a[3], 0, -a[3], -a[2], -a[1])
        val other = longArrayOf(-a[0], b[0], b[1], b[2], b[3], b[2], b[1], b[0])
        for (i in 0..7) {
            synthesis[odd][i][index] = saturate(even[i])
            synthesis[1 - odd][i][index] = saturate(other[i])
        }
        for (i in 0..7) {
            var sum = 0L
            for (j in 0..9) sum += synthesis[odd][i][j].toLong() * SbcTables.synthesisWindow[i][decoderIndex + j]
            pcm[offset + i] = saturate((sum + (1L shl 12)) shr 13).toShort()
        }
        decoderIndex = (decoderIndex + 1) % 10
    }

    private fun bitAllocation(sf: IntArray, bitpool: Int, snr: Boolean = false): IntArray {
        require(sf.size == SUBBANDS && bitpool in 2..(16 * SUBBANDS))
        val offsets = intArrayOf(-3, 0, 0, 0, 0, 0, 1, 2) // 32 kHz, eight subbands
        val needs = IntArray(8) { sb ->
            if (snr) sf[sb] else {
                val loudness = if (sf[sb] == 0) -5 else sf[sb] - offsets[sb]
                if (loudness > 0) loudness shr 1 else loudness
            }
        }
        var bitCount = 0
        var slice = maxOf(0, needs.max()) + 1
        var nextCount = 0
        // Scale factors are 0..15 and the validated budget is at most 128.
        while (nextCount < bitpool) {
            val currentSlice = slice--
            bitCount = nextCount
            for (need in needs) {
                if (need >= currentSlice && need < currentSlice + 15) nextCount++
                if (need == currentSlice) nextCount++
            }
        }
        val bits = IntArray(8) { sb ->
            val n = needs[sb] - slice
            if (n < 2) 0 else minOf(n, 16)
        }
        for (sb in 0..7) {
            if (bitCount >= bitpool) break
            val n = if (bits[sb] in 1..15) 1 else if (needs[sb] == slice + 1 && bitpool > bitCount + 1) 2 else 0
            bits[sb] += n
            bitCount += n
        }
        for (sb in 0..7) {
            if (bitCount >= bitpool) break
            if (bits[sb] < 16) { bits[sb]++; bitCount++ }
        }
        return bits
    }

    /**
     * Length of a complete supported frame at exactly [offset], including CRC validation.
     * Supports 32 kHz, mono, 16 blocks, 8 subbands and loudness or SNR.
     * CRC protects the header/scale factors, not the audio payload.
     */
    fun frameLength(frame: ByteArray, offset: Int = 0): Int? {
        if (offset < 0 || offset > frame.size || frame.size - offset < 8) return null
        if (frame[offset] != SBC_SYNCWORD) return null
        val supportedHeader = (FREQ_32K shl 6) or (3 shl 4) or
            (MODE_MONO shl 2) or (ALLOCATION_LOUDNESS shl 1) or 1
        if ((frame[offset + 1].toInt() and 0xFD) != supportedHeader) return null
        val bitpool = frame[offset + 2].toInt() and 0xFF
        if (bitpool !in 2..(16 * SUBBANDS)) return null
        val length = 4 + SUBBANDS / 2 + (BLOCKS * bitpool + 7) / 8
        if (frame.size - offset < length) return null
        if (frame[offset + 3] != computeCrc(frame, offset)) return null
        return length
    }

    private class ByteArrayBitReader(private val data: ByteArray, startOffset: Int) {
        var bitPos: Int = startOffset * 8

        fun read(numBits: Int): Int {
            var result = 0
            for (i in 0 until numBits) {
                val byteIdx = bitPos / 8
                val bitIdx = 7 - (bitPos % 8)
                result = (result shl 1) or ((data[byteIdx].toInt() shr bitIdx) and 1)
                bitPos++
            }
            return result
        }
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

        // Compute CRC over header bytes 1 and 2 plus scale factors
        result[3] = computeCrc(result)

        return result
    }

    private fun computeCrc(frame: ByteArray, offset: Int = 0): Byte {
        // SBC CRC-8: initial value 0x0F, polynomial 0x1D, MSB first.
        var crc = 0x0F
        for (index in intArrayOf(1, 2, 4, 5, 6, 7)) {
            val value = frame[offset + index].toInt() and 0xFF
            for (bit in 7 downTo 0) {
                val feedback = ((crc ushr 7) xor (value ushr bit)) and 1
                crc = (crc shl 1) and 0xFF
                if (feedback != 0) crc = crc xor 0x1D
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

/** Wire-format constants only. Every audio stream must own a [SbcCodecSession]. */
object SbcCodec {
    const val SBC_SYNCWORD = SbcCodecSession.SBC_SYNCWORD
    const val FREQ_32K = SbcCodecSession.FREQ_32K
    const val BLOCKS = SbcCodecSession.BLOCKS
    const val MODE_MONO = SbcCodecSession.MODE_MONO
    const val ALLOCATION_LOUDNESS = SbcCodecSession.ALLOCATION_LOUDNESS
    const val SUBBANDS = SbcCodecSession.SUBBANDS
    const val BITPOOL = SbcCodecSession.BITPOOL
    const val SAMPLES_PER_FRAME = SbcCodecSession.SAMPLES_PER_FRAME
    const val PCM_BYTES_PER_FRAME = SbcCodecSession.PCM_BYTES_PER_FRAME

}
