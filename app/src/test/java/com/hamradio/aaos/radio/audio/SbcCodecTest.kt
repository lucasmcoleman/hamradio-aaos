package com.hamradio.aaos.radio.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Random

class SbcCodecTest {
    @Before
    fun resetStreams() {
        SbcCodec.resetEncoder()
        SbcCodec.resetDecoder()
    }

    @Test
    fun `sessions keep encoder and decoder history independent`() {
        val first = SbcCodecSession()
        val second = SbcCodecSession()
        val firstFrame = first.encode(referencePcm)!!
        first.encode(ShortArray(128) { Short.MAX_VALUE })
        assertArrayEquals(firstFrame, second.encode(referencePcm))
        val initialPcm = first.decode(externalFrames.copyOfRange(0, 44))!!
        first.decode(externalFrames.copyOfRange(44, 88))
        assertArrayEquals(initialPcm, second.decode(externalFrames.copyOfRange(0, 44)))
        // Resetting one session must also leave the other's ongoing history intact.
        val control = SbcCodecSession()
        control.encode(referencePcm)
        control.decode(externalFrames.copyOfRange(0, 44))
        first.resetEncoder()
        first.resetDecoder()
        assertArrayEquals(control.encode(referencePcm), second.encode(referencePcm))
        assertArrayEquals(control.decode(externalFrames.copyOfRange(44, 88)),
            second.decode(externalFrames.copyOfRange(44, 88)))
    }

    @Test
    fun `encoder matches independent FFmpeg first frame vector`() {
        assertArrayEquals(externalFrames.copyOfRange(0, 44), SbcCodec.encode(referencePcm))
    }

    @Test
    fun `decoder matches independent FFmpeg PCM across consecutive frames`() {
        val actual = externalFrames.asList().chunked(44).flatMap { frame ->
            SbcCodec.decode(frame.toByteArray())!!.toList()
        }
        assertEquals(externalPcm.size, actual.size)
        for (i in actual.indices) {
            // Independent fixed-point implementations differ by a few PCM LSBs.
            assertTrue("sample $i expected ${externalPcm[i]}, actual ${actual[i]}",
                kotlin.math.abs(actual[i].toInt() - externalPcm[i]) <= 5)
        }
    }

    @Test
    fun `reset starts independent streams and rejected frame preserves synthesis history`() {
        val first = SbcCodec.encode(referencePcm)!!
        SbcCodec.encode(referencePcm)
        SbcCodec.resetEncoder()
        assertArrayEquals(first, SbcCodec.encode(referencePcm))
        val expectedFirst = SbcCodec.decode(first)!!
        val expectedSecond = SbcCodec.decode(first)!!
        SbcCodec.resetDecoder()
        assertArrayEquals(expectedFirst, SbcCodec.decode(first))
        assertNull(SbcCodec.decode(first.copyOf(20)))
        assertArrayEquals(expectedSecond, SbcCodec.decode(first))
    }

    @Test
    fun `first use initializes filters and encodes a complete frame`() {
        // Calling encode forces object initialization (the former forward reference crashed).
        val frame = SbcCodec.encode(ShortArray(128))!!
        assertEquals(44, frame.size)
        assertEquals(0x71, frame[1].toInt() and 0xFF)
        assertEquals(18, frame[2].toInt() and 0xFF)
        assertEquals(44, SbcCodec.frameLength(frame))
        assertEquals(128, SbcCodec.decode(frame)!!.size)
        assertNull(SbcCodec.encode(ShortArray(127)))
    }

    @Test
    fun `nonzero PCM frames remain internally decodable`() {
        for (pcm in listOf(
            ShortArray(128) { Short.MAX_VALUE },
            ShortArray(128) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE },
            ShortArray(128) { (it * 400 - 25000).toShort() },
        )) {
            val frame = SbcCodec.encode(pcm)!!
            assertEquals(44, frame.size)
            assertEquals(128, SbcCodec.decode(frame)!!.size)
        }
        // SBC is lossy; these are structural checks, not audio fidelity assertions.
    }

    @Test
    fun `known header CRC is accepted and corrupt protected bytes are rejected`() {
        val frame = ByteArray(44)
        frame[0] = 0x9C.toByte()
        frame[1] = 0x71
        frame[2] = 18
        frame[3] = 0xD5.toByte() // CRC-8 of 71 12 00 00 00 00, init 0F, polynomial 1D.
        assertNotNull(SbcCodec.decode(frame))
        for (index in listOf(2, 3, 4, 5, 6, 7)) {
            val corrupt = frame.copyOf()
            corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            assertNull(SbcCodec.decode(corrupt))
        }
    }

    @Test
    fun `all truncated prefixes and invalid offsets are rejected`() {
        val frame = SbcCodec.encode(ShortArray(128))!!
        for (length in 0 until frame.size) {
            assertNull("length=$length", SbcCodec.decode(frame.copyOf(length)))
            assertNull(SbcCodec.frameLength(frame.copyOf(length)))
        }
        for (offset in listOf(Int.MIN_VALUE, -1, frame.size, frame.size + 1, Int.MAX_VALUE)) {
            assertNull(SbcCodec.decode(frame, offset))
            assertNull(SbcCodec.frameLength(frame, offset))
        }
        val prefixed = byteArrayOf(0, 1, 2) + frame + frame
        assertEquals(128, SbcCodec.decode(prefixed)!!.size)
        assertEquals(44, SbcCodec.frameLength(prefixed, 3))
        assertNull(SbcCodec.frameLength(prefixed))
    }

    @Test
    fun `unsupported headers are rejected even with valid CRC`() {
        // Change each frequency, block, mode, allocation and subband field.
        for (mask in listOf(0x40, 0x80, 0x10, 0x20, 0x04, 0x08, 0x02, 0x01)) {
            val frame = syntheticFrame(18)
            frame[1] = (0x71 xor mask).toByte()
            setCrc(frame)
            assertNull(SbcCodec.decode(frame))
            assertNull(SbcCodec.frameLength(frame))
        }
    }

    @Test(timeout = 5000)
    fun `every bitpool value is bounded with varied scale factors`() {
        for (bitpool in 0..255) {
            for (scale in listOf(0x00, 0xFF, 0x1F, 0xE2)) {
                val frame = syntheticFrame(bitpool, scale)
                if (bitpool in 2..128) {
                    assertEquals(8 + 2 * bitpool, SbcCodec.frameLength(frame))
                    assertEquals(128, SbcCodec.decode(frame)!!.size)
                } else {
                    assertNull(SbcCodec.frameLength(frame))
                    assertNull(SbcCodec.decode(frame))
                }
            }
        }
    }

    @Test(timeout = 5000)
    fun `random malformed inputs do not throw or hang`() {
        val random = Random(859)
        repeat(2000) {
            val bytes = ByteArray(random.nextInt(530))
            random.nextBytes(bytes)
            SbcCodec.decode(bytes)
        }
    }

    private fun syntheticFrame(bitpool: Int, scale: Int = 0): ByteArray {
        val frame = ByteArray(8 + 2 * bitpool)
        frame[0] = 0x9C.toByte()
        frame[1] = 0x71
        frame[2] = bitpool.toByte()
        for (index in 4..7) frame[index] = scale.toByte()
        setCrc(frame)
        return frame
    }

    private fun setCrc(frame: ByteArray) {
        // Byte-at-a-time formulation, independent of the production bit reader.
        var crc = 0x0F
        for (index in listOf(1, 2, 4, 5, 6, 7)) {
            crc = crc xor (frame[index].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x11D else crc shl 1
            }
        }
        frame[3] = crc.toByte()
    }

    // Generated independently with FFmpeg 9.0, not this codec:
    // ffmpeg -f s16le -ar 32000 -ac 1 -i input.pcm -c:a sbc -b:a 88000 -f sbc output.sbc
    // ffmpeg -f sbc -i output.sbc -f s16le -c:a pcm_s16le decoded.pcm
    // Input: round(10000*sin(2*pi*1000*i/32000) + 3000*sin(2*pi*2750*i/32000)).
    // Three frames exercise synthesis history across the ten-block ring boundary.
    private val referencePcm = shortArrayOf(
        0, 3493, 6473, 8552, 9565, 9597, 8945, 8021, 7228, 6840, 6920, 7304, 7656, 7570, 6698, 4861,
        2121, -1222, -4698, -7779, -10013, -11139, -11142, -10248, -8852, -7398, -6253, -5603, -5404, -5408, -5241, -4524,
        -3000, -622, 2413, 5703, 8738, 11027, 12224, 12217, 11148, 9368, 7336, 5490, 4129, 3333, 2956, 2680,
        2121, 959, -956, -3541, -6486, -9325, -11558, -12775, -12772, -11595, -9533, -7032, -4577, -2559, -1181, -409,
        0, 409, 1181, 2559, 4577, 7032, 9533, 11595, 12772, 12775, 11558, 9325, 6486, 3541, 956, -959,
        -2121, -2680, -2956, -3333, -4129, -5490, -7336, -9368, -11148, -12217, -12224, -11027, -8738, -5703, -2413, 622,
        3000, 4524, 5241, 5408, 5404, 5603, 6253, 7398, 8852, 10248, 11142, 11139, 10013, 7779, 4698, 1222,
        -2121, -4861, -6698, -7570, -7656, -7304, -6920, -6840, -7228, -8021, -8945, -9597, -9565, -8552, -6473, -3493,
    )
    private val externalFrames = (
        "9c71122ddb9777667eed5fbb582ed613c1a75a2dac8544d4fdb5a90d6f935554d4f535a84d70435538d4f9b5" +
        "9c71126fdb100000abed3036d5409cfe23ab7b300b854e5cfde4aa92b03915535cf9dbab04b06465459cfa1a" +
        "9c71126fdb100000abed3036d5409cfe23ab7b300b854e5cfde4aa92b03915535cf9dbab04b06465459cfa1a"
    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val externalPcm = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, -1, -1, -1, 0, 0, 2, 4, 5, 6, 5, 4, 3, 4, 1, -8,
        -13, -13, -8, -1, 6, 16, 30, 47, 58, 50, 28, 4, -5, -27, -86, -145,
        -169, -171, -146, -94, -59, -21, 74, 186, 216, 71, -258, -515, -449, -178, 45, 124,
        -6, -189, -81, 148, 87, 48, 277, 70, -537, 212, 3160, 6556, 8627, 9535, 9777, 9150,
        7966, 7149, 6892, 6891, 7191, 7644, 7598, 6715, 5002, 2300, -1248, -4864, -7859, -9939, -10916, -10825,
        -9997, -8817, -7582, -6578, -5926, -5573, -5361, -5012, -4200, -2718, -500, 2330, 5439, 8367, 10651, 11930,
        12050, 11138, 9489, 7518, 5650, 4202, 3290, 2817, 2502, 1983, 905, -911, -3419, -6325, -9160, -11413,
        -12664, -12720, -11627, -9680, -7310, -4965, -2991, -1562, -653, -82, 464, 1289, 2627, 4551, 6926, 9419,
        11563, 12880, 12995, 11798, 9469, 6466, 3367, 714, -1151, -2168, -2588, -2808, -3244, -4181, -5682, -7584,
        -9546, -11162, -12035, -11902, -10673, -8462, -5565, -2406, 557, 2917, 4476, 5239, 5429, 5423, 5597, 6227,
        7366, 8842, 10268, 11183, 11187, 10055, 7805, 4702, 1204, -2139, -4874, -6702, -7561, -7634, -7271, -6886,
        -6811, -7221, -8030, -8962, -9611, -9569, -8543, -6454, -3469, 7, 3488, 6460, 8539, 9563, 9606, 8957,
        8024, 7229, 6831, 6905, 7291, 7652, 7574, 6699, 4849, 2105, -1243, -4713, -7780, -9996, -11107, -11108,
        -10220, -8850, -7415, -6278, -5621, -5406, -5390, -5207, -4487, -2982, -627, 2389, 5668, 8708, 11006, 12217,
        12216, 11155, 9374, 7335, 5480, 4113, 3315, 2939, 2669, 2127, 975, -938, -3531, -6489, -9339, -11576,
        -12784, -12773, -11585, -9516, -7017, -4569, -2561, -1185, -408, 0, 411, 1181, 2551, 4559, 7005, 9504,
        11571, 12768, 12786, 11576, 9338, 6489, 3532, 942, -971, -2111, -2656, -2927, -3312, -4126, -5501, -7351,
        -9374, -11146, -12201, -12202, -11008, -8732, -5713, -2431, 610, 2989, 4525, 5252, 5422, 5416, 5604, 6244,
        7380, 8840, 10244, 11145, 11148, 10027, 7792, 4701, 1210, -2134, -4872, -6702, -7561, -7633, -7269, -6884,
        -6810, -7222, -8033, -8964, -9613, -9570, -8543, -6454, -3469, 7, 3488, 6460, 8539, 9563, 9606, 8957,
        8024, 7229, 6831, 6905, 7291, 7652, 7574, 6699, 4849, 2105, -1243, -4713, -7780, -9996, -11107, -11108,
        -10220, -8850, -7415, -6278, -5621, -5406, -5390, -5207, -4487, -2982, -627, 2389, 5668, 8708, 11006, 12217,
        12216, 11155, 9374, 7335, 5480, 4113, 3315, 2939, 2669, 2127, 975, -938, -3531, -6489, -9339, -11576,
    )
}
