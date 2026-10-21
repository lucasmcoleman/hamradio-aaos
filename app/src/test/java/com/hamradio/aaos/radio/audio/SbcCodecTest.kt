package com.hamradio.aaos.radio.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Random
import java.util.Base64
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SbcCodecTest {
    private lateinit var codec: SbcCodecSession
    @Before
    fun resetStreams() {
        codec = SbcCodecSession()
    }

    @Test
    fun `sixteen fullscale TX frames are bit exact against upstream Dart encoder`() {
        val bytes = ByteArrayOutputStream()
        for (frame in 0 until 16) {
            val pcm = ShortArray(128) { position ->
                val i = frame * 128 + position
                when (frame % 4) {
                    0 -> if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
                    1 -> (((i * 7919 + 12345) and 65535) - 32768).toShort()
                    2 -> Short.MAX_VALUE
                    else -> Short.MIN_VALUE
                }
            }
            bytes.write(codec.encode(pcm)!!)
        }
        assertArrayEquals(Base64.getDecoder().decode(dartFrames.getValue("bp18-loudness")), bytes.toByteArray())
    }

    @Test
    fun `sixteen frame RX streams at both bitpools and allocations are bit exact against upstream Dart`() {
        for ((name, encoded) in dartFrames) {
            val session = SbcCodecSession()
            val frames = Base64.getDecoder().decode(encoded)
            val pcm = ByteArrayOutputStream()
            var offset = 0
            var frameCount = 0
            while (offset < frames.size) {
                val length = session.frameLength(frames, offset)!!
                val samples = session.decode(frames, offset)!!
                val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in samples) bytes.putShort(sample)
                pcm.write(bytes.array())
                offset += length
                frameCount++
            }
            assertEquals(name, 16, frameCount)
            assertEquals(name, 4096, pcm.size())
            val hash = MessageDigest.getInstance("SHA-256").digest(pcm.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            assertEquals(name, dartPcmSha256.getValue(name), hash)
        }
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
        assertArrayEquals(externalFrames.copyOfRange(0, 44), codec.encode(referencePcm))
    }

    @Test
    fun `decoder matches independent FFmpeg PCM across consecutive frames`() {
        val actual = externalFrames.asList().chunked(44).flatMap { frame ->
            codec.decode(frame.toByteArray())!!.toList()
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
        val first = codec.encode(referencePcm)!!
        codec.encode(referencePcm)
        codec.resetEncoder()
        assertArrayEquals(first, codec.encode(referencePcm))
        val expectedFirst = codec.decode(first)!!
        val expectedSecond = codec.decode(first)!!
        codec.resetDecoder()
        assertArrayEquals(expectedFirst, codec.decode(first))
        assertNull(codec.decode(first.copyOf(20)))
        assertArrayEquals(expectedSecond, codec.decode(first))
    }

    @Test
    fun `first use initializes filters and encodes a complete frame`() {
        // Calling encode forces object initialization (the former forward reference crashed).
        val frame = codec.encode(ShortArray(128))!!
        assertEquals(44, frame.size)
        assertEquals(0x71, frame[1].toInt() and 0xFF)
        assertEquals(18, frame[2].toInt() and 0xFF)
        assertEquals(44, codec.frameLength(frame))
        assertEquals(128, codec.decode(frame)!!.size)
        assertNull(codec.encode(ShortArray(127)))
    }

    @Test
    fun `nonzero PCM frames remain internally decodable`() {
        for (pcm in listOf(
            ShortArray(128) { Short.MAX_VALUE },
            ShortArray(128) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE },
            ShortArray(128) { (it * 400 - 25000).toShort() },
        )) {
            val frame = codec.encode(pcm)!!
            assertEquals(44, frame.size)
            assertEquals(128, codec.decode(frame)!!.size)
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
        assertNotNull(codec.decode(frame))
        for (index in listOf(2, 3, 4, 5, 6, 7)) {
            val corrupt = frame.copyOf()
            corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            assertNull(codec.decode(corrupt))
        }
    }

    @Test
    fun `all truncated prefixes and invalid offsets are rejected`() {
        val frame = codec.encode(ShortArray(128))!!
        for (length in 0 until frame.size) {
            assertNull("length=$length", codec.decode(frame.copyOf(length)))
            assertNull(codec.frameLength(frame.copyOf(length)))
        }
        for (offset in listOf(Int.MIN_VALUE, -1, frame.size, frame.size + 1, Int.MAX_VALUE)) {
            assertNull(codec.decode(frame, offset))
            assertNull(codec.frameLength(frame, offset))
        }
        val prefixed = byteArrayOf(0, 1, 2) + frame + frame
        assertEquals(128, codec.decode(prefixed)!!.size)
        assertEquals(44, codec.frameLength(prefixed, 3))
        assertNull(codec.frameLength(prefixed))
    }

    @Test
    fun `unsupported headers are rejected even with valid CRC`() {
        // Change each frequency, block, mode and subband field.
        for (mask in listOf(0x40, 0x80, 0x10, 0x20, 0x04, 0x08, 0x01)) {
            val frame = syntheticFrame(18)
            frame[1] = (0x71 xor mask).toByte()
            setCrc(frame)
            assertNull(codec.decode(frame))
            assertNull(codec.frameLength(frame))
        }
    }

    @Test(timeout = 5000)
    fun `every bitpool value is bounded with varied scale factors`() {
        for (bitpool in 0..255) {
            for (scale in listOf(0x00, 0xFF, 0x1F, 0xE2)) {
                val frame = syntheticFrame(bitpool, scale)
                if (bitpool in 2..128) {
                    assertEquals(8 + 2 * bitpool, codec.frameLength(frame))
                    assertEquals(128, codec.decode(frame)!!.size)
                } else {
                    assertNull(codec.frameLength(frame))
                    assertNull(codec.decode(frame))
                }
            }
        }
    }

    @Test
    fun `extreme payload codewords saturate instead of changing sign`() {
        for (bits in 1..16) {
            val positive = codec.dequantizeSample((1 shl bits) - 1, bits, 15, -1)
            assertEquals("bits=$bits", Short.MAX_VALUE.toInt(), positive)
            assertTrue(codec.dequantizeSample(0, bits, 15, -1) <= 0)
        }
    }

    @Test(timeout = 5000)
    fun `valid header random payloads and scale factors remain bounded for both allocations`() {
        val random = Random(860)
        for (snr in listOf(false, true)) {
            repeat(2000) {
                val frame = syntheticFrame(2 + random.nextInt(127))
                for (i in 4 until frame.size) frame[i] = random.nextInt(256).toByte()
                frame[1] = if (snr) 0x73 else 0x71
                setCrc(frame)
                assertEquals(frame.size, codec.frameLength(frame))
                assertEquals(128, codec.decode(frame)!!.size)
            }
        }
    }

    @Test(timeout = 5000)
    fun `random malformed inputs do not throw or hang`() {
        val random = Random(859)
        repeat(2000) {
            val bytes = ByteArray(random.nextInt(530))
            random.nextBytes(bytes)
            codec.decode(bytes)
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

    // Independently generated by unmodified HTCommander commit 09dfa3e using Dart
    // 3.13.6. Sixteen contiguous frames cross the filter-state ring repeatedly.
    // Input alternates fullscale extrema, integer ramp, positive DC, negative DC.
    // These bytes and expected PCM hashes are external fixtures, not Kotlin outputs.
    private val dartFrames = mapOf(
        "bp18-loudness" to (
            "nHES/qq6u89qqtqqtqquDJPA0FSMlqqlqqlqqlqqlqqlqqlqqlqqlqqlqqmccRKAvt3MzndVHdVGdVIdVDZAbpmZpmUpmQpmZxlauYTpYatEdmFV" +
            "mFSiFZxxEgXt3dzMfKWiqmgsyicx86i83E821821821821821821821821821821nHESIv3MvLu9tu5tuxtuqFtATE91tBts9ts9ts9ts9ts9ts9" +
            "ts9ts9ts9tuccRI97Mu8zwNrQNrQtrRGSYYlIxEHtrHtrHtrHtrHtrHtrHtrHtrHtrHtrJxxEk2+3czebVUbVUbVUbVULlZdZVtmFNWFlRF0hVUh" +
            "VMhVbilyklKkVKkVnHESbO7dvcxy2OJymCSKJ2rzRvztvztvztvztvztvztvztvztvztvztvztuccRIi/cy8u7227m27G26oW0BMT3W0G2z22z22" +
            "z22z22z22z22z22z22z225xxEj3sy7zPA2tA2tC2tEZJhiUjEQe2se2se2se2se2se2se2se2se2se2snHESzL7dzc53VR3VSHVRoVTZkW5gVohU" +
            "ohV4hnWklGkUlkU1kXDZFEZVTZGccRLF7szMvImZnoWoIWoGmvRBvO1fO1fO1fO1fO1fO1fO1fO1fO1fO1fO1ZxxEiL9zLy7vbbubbsbbqhbQExP" +
            "dbQbbPbbPbbPbbPbbPbbPbbPbbPbbPbbnHESPezLvM8Da0Da0La0RkmGJSMRB7ax7ax7ax7ax7ax7ax7ax7ax7ax7ayccRJOzt3d3m1VG1VG1VE1" +
            "VHFWY5GUpGUpFUpVYWRUmVUWRaVVWVlURlUVpZxxEmzu3b3MihMewLgxKm6R7Zc86b87b87b87b87b87b87b87b87b87b87bnHESIv3MvLu9tu5t" +
            "uxtuqFtATE91tBts9ts9ts9ts9ts9ts9ts9ts9ts9ts="
        ),
        "bp18-snr" to (
            "nHMScqq6u89at9at9at1aRyNA8bM9atBas9as9as9as9as9as9as9as9as+ccxIMvt3MznbUHbUHbUHbUWaBqVaaVaKVaKVaMdVrjhqjhriSJxWJ" +
            "xWIxWJxzEont3dzMeUyhVKdZmg5l51G5uM5tt5tt5tt5tt5tt5tt5tt5tt5tt5ttnHMSrv3MvLu922522xm2qi1AJg9q1Bm09209209209209209" +
            "20920920922ccxKx7Mu8zwKrwKrwqrhCzoRB4qp3qp3qp3qp3qp3qp3qp3qp3qp3qp3qp5xzEsG+3czedtYdtYdtYdtQixrt5TquaqObuJjnFUnF" +
            "TjFTimTaoibMjXMjnHMS4O7dvcxy2OJymCSKJ2rzRvztvztvztvztvztvztvztvztvztvztvztuccxKu/cy8u73bbnbbGbaqLUAmD2rUGbT3bT3b" +
            "T3bT3bT3bT3bT3bT3bT3bZxzErHsy7zPAqvAqvCquELOhEHiqneqneqneqneqneqneqneqneqneqneqnnHMSQL7dzc521h21h21iG1iaxOchyMVu" +
            "MWOKWtqiJsyNcxtbBs5UxJVM5VOccxJJ7szMvIMmHQaHQaHNaOhGedVedVedVedVedVedVedVedVedVedVedVZxzEq79zLy7vdtudtsZtqotQCYP" +
            "atQZtPdtPdtPdtPdtPdtPdtPdtPdtPdtnHMSsezLvM8Cq8Cq8Kq4Qs6EQeKqd6qd6qd6qd6qd6qd6qd6qd6qd6qd6qeccxLCzt3d3lqqFqqFqqFq" +
            "qGKs5yM1SMxSK1Sq4sjFMq0si4qqkrK0jKwrS5xzEuDu3b3MihMewLgxKm6R7Zc86b87b87b87b87b87b87b87b87b87b87bnHMSrv3MvLu92252" +
            "2xm2qi1AJg9q1Bm0920920920920920920920920920="
        ),
        "bp40-loudness" to (
            "nHEocqq6u89973ve74HPg97vic58Hs6lDsuRTvDFj2oHTvEb8seFznwgx3oPc+Dnfe973ud973ve533ve97nfe973ud973ve533ve97nfe973ud9" +
            "73ve55xxKAy+3czOffe973B9973vcHX3ua6Akfi6M1Ex16gFtsql5lWpmsZeO7hO1V34mArVXdmYpzdhcjau+kNEdjbaP2aWsvkmQLjebRlwSF5d" +
            "nhI4Mk4d1DiccSiJ7d3czH87ll5GjKyxW8GGvbkVtIxfQNTS/OwxF8L810opWvz4PdCH/Pe973f8973vd/z3ve93/Pe973f8973vd/z3ve93/Pe9" +
            "73f8973vd/z3ve93nHEorv3MvLu+ve93u75B73e7sjXwdzuqVQ7HJEECvYKeP7svGNpBuhB3Oz++D2e7QD3vd7s/ve93uz+973e7P73vd7s/ve93" +
            "uz+973e7P73vd7s/ve93u5xxKLHsy7zPAHvd3u8Bc93e7w2LmiDOF0SpGW6F2cduJ4+S0qanfYOaIMd/e93e53973d7nf3vd3ud/e93e53973d7n" +
            "f3vd3ud/e93e53973d7nf3vd3ueccSjBvt3M3n33ve9wffe973B5+D3vgIIIujJhPjvN88iLGGmuZ7bbP2OGNto/ZYay+SZAmN5tGXBYXl2eElgy" +
            "Th3UWHY7lp5X2awwu5ZFrbhUliWdwFKWnHEo4O7dvcx4tiYNh4p8s4cthp1sy2qMbi5KefzlHjCV/QQe73f8+97vd/z73u93/Pve73f8+97vd/z7" +
            "3u93/Pve73f8+97vd/z73u93/Pve73f8+97vd5xxKK79zLy7vr3vd7u+Qe93u7I18Hc7qlUOxyRBAr2Cnj+7LxjaQboQdzs/vg9nu0A973e7P73v" +
            "d7s/ve93uz+973e7P73vd7s/ve93uz+973e7P73vd7uccSix7Mu8zwB73d7vAXPd3u8Ni5ogzhdEqRluhdnHbiePktKmp32DmiDHf3vd3ud/e93e" +
            "53973d7nf3vd3ud/e93e53973d7nf3vd3ud/e93e53973d7nnHEoQL7dzc59973vcH33ve9wigg9z4BqGDYRQd6cvAroxm6lLUlmXaHRODJOIdEo" +
            "ejuWl0fZrDC1xkWtuFG2JZ3AUbZBbE0Oh9T5Zoc5TSpZ62hU6mVqh5xxKEnuzMy8kGIzS+h8iaHcGYSZn8yhhINX3YL9HQc7C/0DXRgr/PvdCDP8" +
            "+973u/z73ve7/Pve97v8+973u/z73ve7/Pve97v8+973u/z73ve7/Pve97uccSiu/cy8u76973e7vkHvd7uyNfB3O6pVDsckQQK9gp4/uy8Y2kG6" +
            "EHc7P74PZ7tAPe93uz+973e7P73vd7s/ve93uz+973e7P73vd7s/ve93uz+973e7nHEosezLvM8Ae93e7wFz3d7vDYuaIM4XRKkZboXZx24nj5LS" +
            "pqd9g5ogx3973d7nf3vd3ud/e93e53973d7nf3vd3ud/e93e53973d7nf3vd3ud/e93e55xxKMLO3d3effe973B9973vcH4IPc+Aahe18WF+ajVP" +
            "t629OPGnYa29EaZRncEQlmFsTW2HqPlmJ1llKlnraGjqZaqHwMXSU5VxFULUZmUUurRmUQY2tleccSjg7t29zIyaCnNtgMjQ7bqE0U1MyqECUsdy" +
            "9XliUnb9C19PePsDns93/Pve73f8+97vd/z73u93/Pve73f8+97vd/z73u93/Pve73f8+97vd/z73u93nHEorv3MvLu+ve93u75B73e7sjXwdzuq" +
            "VQ7HJEECvYKeP7svGNpBuhB3Oz++D2e7QD3vd7s/ve93uz+973e7P73vd7s/ve93uz+973e7P73vd7s/ve93uw=="
        ),
        "bp40-snr" to (
            "nHMo/qq6u893e73vf3d7vfB+h3PB7XiUdjkVdOMsdqBAS4i/Lj+Hc8IOQHd7Pg8/d3u97z93e73vP3d7ve8/d3u97z93e73vP3d7ve8/d3u97z93" +
            "e73vP5xzKIC+3czOd++93cB3773dwGfvOZ4Ch/G6JUU3TygK27rL5iqom0xeNuNLat3yYguK3fJinO5hYNur1UNJ2jt0P05cq/KmAuHZ2xldJVmb" +
            "neTiKTyd5OKccygF7d3czH89yy5GjKZYrcGGxtyKtIxXoGnS/OYYi8L826UUWvz0HueH/Pve93f8+973d/z73vd3/Pve93f8+973d/z73vd3/Pve" +
            "93f8+973d/z73vd3nHMoIv3MvLu+fe93u72B73e7sm3wdzuqqQ7HJEEGvYKeP3cvGNpBchB3Oz9+D2e7QHnvd7s/fe93uz9973e7P33vd7s/fe93" +
            "uz9973e7P33vd7s/fe93u5xzKD3sy7zPAPe7u78A57u7vw0XNDs8FolSNbmFs47Yn48lpUqffQc0OyB+97u7n373u7uffve7u59+97u7n373u7uf" +
            "fve7u59+97u7n373u7uffve7u5+ccyhNvt3M3nfve7vAd+97u8B4EHu8A3hRdEMEOPebzqKMcNMzIKuWfowaO3R+lFyr8kwFIdnaMrslWZs7yuIp" +
            "PDvKonj3LXLe1phhbRlGu3DM3CZbgM0cnHMobO7dvcx4sxMNh4p6WacthqK2S2qMaxcqefzijxCV/QIPb3f8+e9vd/z97293/P3vb3f8/e9vd/z9" +
            "7293/P3vb3f8/e9vd/z97293/P3vb3f8/e9vd5xzKCL9zLy7vn3vd7u9ge93u7Jt8Hc7qqkOxyRBBr2Cnj93LxjaQXIQdzs/fg9nu0B573e7P33v" +
            "d7s/fe93uz9973e7P33vd7s/fe93uz9973e7P33vd7uccyg97Mu8zwD3u7u/AOe7u78NFzQ7PBaJUjW5hbOO2J+PJaVKn30HNDsgfve7u59+97u7" +
            "n373u7uffve7u59+97u7n373u7uffve7u59+97u7n373u7ufnHMozL7dzc5373u9wHfve73AiDB7PgNokGvFBdqZeCuiub1KNShpW0PE4ik8Q8Si" +
            "ePcs3R7WmGFXGUa7cMbcJluAxtw1mJm6H8PSzJzlRJSzraJTlMqqIJxzKMXuzMy8kGUZpeF8jNDuFIScz+YUhIGr7oT9HoOdB/0Jroun/P3ug7f8" +
            "/e97t/z973u3/P3ve7f8/e97t/z973u3/P3ve7f8/e97t/z973u3/P3ve7eccygi/cy8u75973e7vYHvd7uybfB3O6qpDsckQQa9gp4/dy8Y2kFy" +
            "EHc7P34PZ7tAee93uz9973e7P33vd7s/fe93uz9973e7P33vd7s/fe93uz9973e7nHMoPezLvM8A97u7vwDnu7u/DRc0OzwWiVI1uYWzjtifjyWl" +
            "Sp99BzQ7IH73u7uffve7u59+97u7n373u7uffve7u59+97u7n373u7uffve7u59+97u7n5xzKE7O3d3ed973veB33ve94Hgg9z4BaF7XxYJ5qNU+" +
            "7qb048bPVrb0Ro1GdwRCjVWxNbYvo+WYnVJkqWet0GOplqoQsxdJTkxkVQtRrWRS6tHORBja2W6ccyhs7t29zIydBTNtgNBobbqE1KasyqEBKUdy" +
            "9YCxMnb9Da+PePsJz293/P3vb3f8/e9vd/z97293/P3vb3f8/e9vd/z97293/P3vb3f8/e9vd/z97293nHMoIv3MvLu+fe93u72B73e7sm3wdzuq" +
            "qQ7HJEEGvYKeP3cvGNpBchB3Oz9+D2e7QHnvd7s/fe93uz9973e7P33vd7s/fe93uz9973e7P33vd7s/fe93uw=="
        ),
    )
    private val dartPcmSha256 = mapOf(
        "bp18-loudness" to "6157a23cafbcf536c68c8959a8718fec146f46af8bb16ad429d27c05c25add8f",
        "bp18-snr" to "e8af6287448a5a138e29aef9f90580e50895a201033835231ee7cfa6c7361661",
        "bp40-loudness" to "c0048ba30e09e49e27f3291ec2f9e83db7f6f62989ea102211122baef4484b2d",
        "bp40-snr" to "4866773e1afd7ef50c39a53a6648a7feac77d87e6fd9e139e8f97fddc8375d4b",
    )
}
