package com.hamradio.aaos.radio.transport

import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        assertEquals(WriteResult.Failed(null), deliverWrite(tryStart = { true }, awaitAck = { null }))
        assertEquals(WriteResult.Failed(133), deliverWrite(tryStart = { true }, awaitAck = { 133 }))
    }

    @Test fun picksWriteTypeFromProperties() {
        val write = BluetoothGattCharacteristic.PROPERTY_WRITE
        val noResp = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, writeTypeFor(write or noResp))
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, writeTypeFor(write))
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, writeTypeFor(noResp))
    }
}
