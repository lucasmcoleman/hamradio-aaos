package com.hamradio.aaos.di

import android.bluetooth.BluetoothAdapter
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 1.3.8 rejected every bridge address in the manual field, so bridge mode couldn't be set. */
class RadioPrefsAddressTest {
    @Before fun setup() {
        mockkStatic(BluetoothAdapter::class)
        every { BluetoothAdapter.checkBluetoothAddress(any()) } answers {
            Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(firstArg<String>())
        }
    }

    @After fun teardown() = unmockkStatic(BluetoothAdapter::class)

    @Test fun acceptsBridgeAddressesAsTheManualFieldSendsThem() {
        assertTrue(RadioPrefs.isAcceptedDeviceAddress("BRIDGE:"))
        assertTrue(RadioPrefs.isAcceptedDeviceAddress("BRIDGE:AUTO"))
        assertTrue(RadioPrefs.isAcceptedDeviceAddress("BRIDGE:192.168.0.243"))
        assertTrue(RadioPrefs.isAcceptedDeviceAddress("bridge:192.168.4.1:9000"))
    }

    @Test fun stillChecksMacAddresses() {
        assertTrue(RadioPrefs.isAcceptedDeviceAddress("38:D2:00:01:68:A8"))
        assertFalse(RadioPrefs.isAcceptedDeviceAddress("38:D2:00:01:68"))
        assertFalse(RadioPrefs.isAcceptedDeviceAddress("192.168.0.243"))
    }
}
