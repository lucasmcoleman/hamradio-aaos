package com.hamradio.aaos.radio.transport

import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 10-23 truck test dropped every command after one slow ack (job #875). */
class BleWriteTest {
    @Test fun retriesBusyStartThenWaitsForAck() = runTest {
        var attempts = 0
        val result = deliverWrite(tryStart = { ++attempts >= 4 }, awaitAck = { 0 }, busyRetryMs = 1_000, pollMs = 50)
        assertEquals(WriteResult.Acked, result)
        assertEquals(4, attempts)
    }

    @Test fun givesUpOnlyAfterTheBusyWindow() = runTest {
        var attempts = 0
        val result = deliverWrite(tryStart = { attempts++; false }, awaitAck = { 0 }, busyRetryMs = 500, pollMs = 50)
        assertEquals(WriteResult.NeverStarted, result)
        assertEquals(11, attempts)
    }

    @Test fun reportsMissingOrBadAck() = runTest {
        assertEquals(WriteResult.NoAck, deliverWrite(tryStart = { true }, awaitAck = { null }))
        assertEquals(WriteResult.Failed(133), deliverWrite(tryStart = { true }, awaitAck = { 133 }))
    }

    @Test fun picksWriteTypeFromProperties() {
        val write = BluetoothGattCharacteristic.PROPERTY_WRITE
        val noResp = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, writeTypeFor(write or noResp))
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, writeTypeFor(write))
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, writeTypeFor(noResp))
    }

    /**
     * codex #947: A times out, A's callback arrives late. B must never be started on that
     * session (it would take A's status as its own); the queue ends through onFatal instead.
     */
    @Test fun lateAckCannotBeClaimedByTheNextWrite() = runTest {
        val acks = Channel<Int>(Channel.CONFLATED)
        var inFlight = false
        val started = mutableListOf<String>()
        val fatal = mutableListOf<String>()
        val queue = Channel<ByteArray>(8).apply {
            trySend(byteArrayOf(0xA)); trySend(byteArrayOf(0xB)); close()
        }
        launch { delay(15_000); inFlight = false; acks.send(0) }   // A's callback, too late
        runWriteQueue(
            queue = queue,
            tryStart = { bytes -> if (inFlight) false else { inFlight = true; started += "%02x".format(bytes[0]); true } },
            awaitAck = { withTimeoutOrNull(10_000) { acks.receive() } },
            onRejected = { _, _ -> },
            onFatal = { fatal += it },
        )
        assertEquals(listOf("0a"), started)
        assertEquals(1, fatal.size)
        assertTrue(fatal[0], fatal[0].contains("no answer"))
    }

    @Test fun rejectedWriteKeepsTheQueueGoing() = runTest {
        val statuses = ArrayDeque(listOf(3, 0))
        val started = mutableListOf<Int>()
        val queue = Channel<ByteArray>(8).apply { trySend(byteArrayOf(1)); trySend(byteArrayOf(2)); close() }
        val rejected = mutableListOf<Int>()
        runWriteQueue(queue, { started += it[0].toInt(); true }, { statuses.removeFirst() },
            onRejected = { _, s -> rejected += s }, onFatal = { error(it) })
        assertEquals(listOf(1, 2), started)
        assertEquals(listOf(3), rejected)
    }
}
