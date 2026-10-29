package com.hamradio.aaos.radio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.hamradio.aaos.radio.audio.AudioFocusControl
import com.hamradio.aaos.radio.audio.RadioAudioControl
import com.hamradio.aaos.di.RadioTransportFactory
import com.hamradio.aaos.radio.protocol.*
import com.hamradio.aaos.radio.transport.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RadioControllerTest {
    private val context = mockk<Context>()
    private val factory = mockk<RadioTransportFactory>()
    @Before fun setup() {
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns mockk<AudioManager>(relaxed = true)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }
    @After fun cleanup() = unmockkAll()

    @Test fun loopbackIsSelectedOnlyForMockTransport() = runTest {
        val simulated = selectRadioAudio(context, MockTransport(backgroundScope))
        val real = selectRadioAudio(context, RfcommTransport(context, "unused", backgroundScope))
        try {
            assertTrue(simulated is com.hamradio.aaos.radio.audio.MockRadioAudioChannel)
            assertTrue(real is com.hamradio.aaos.radio.audio.RadioAudioChannel)
            assertNull(selectRadioAudio(context, DisconnectedTransport()))
        } finally {
            simulated?.close()
            real?.close()
        }
    }

    @Test fun bridgeTransportGetsBridgeAudio() = runTest {
        val audio = selectRadioAudio(context, BridgeTransport("bridge:127.0.0.1", backgroundScope))
        try {
            assertTrue(audio is com.hamradio.aaos.radio.audio.BridgeAudioChannel)
            assertFalse("not connected: no transmit", audio!!.startTransmit())
        } finally { audio?.close() }
    }

    @Test fun pttOverBridgeAudioStartsAndStopsTransmit() = runTest {
        val connected = MutableStateFlow(false)
        val transmitting = MutableStateFlow(false)
        val audio = mockk<com.hamradio.aaos.radio.audio.BridgeAudioChannel>()
        every { audio.isConnected } returns connected
        every { audio.isTransmitting } returns transmitting
        coEvery { audio.connect() } answers { connected.value = true }
        coEvery { audio.disconnect() } answers { transmitting.value = false; connected.value = false }
        coEvery { audio.close() } answers { transmitting.value = false; connected.value = false }
        coEvery { audio.startTransmit() } answers { transmitting.value = true; true }
        coEvery { audio.stopTransmit() } answers { transmitting.value = false }
        val focus = FakeFocus()
        val controller = RadioController(FakeTransport(), factory, context, backgroundScope, focus) { audio }
        controller.connect(); runCurrent(); advanceTimeBy(1001); runCurrent()
        controller.pttDown(); runCurrent()
        coVerify(exactly = 1) { audio.startTransmit() }
        assertTrue(controller.isPttActive.value)
        assertTrue(focus.held)
        controller.pttUp(); runCurrent()
        coVerify { audio.stopTransmit() }
        assertFalse(controller.isPttActive.value)
        assertFalse(focus.held)
        controller.disconnect(); runCurrent()
    }

    private class FakeTransport(val pending: Boolean = false) : IRadioTransport {
        val state = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val connectionState = state.asStateFlow()
        val messages = MutableSharedFlow<BenshiMessage>(extraBufferCapacity = 16)
        override val inboundMessages = messages.asSharedFlow()
        override val isConnected get() = state.value == ConnectionState.CONNECTED
        var connects = 0
        var disconnects = 0
        val sent = mutableListOf<BenshiMessage>()
        override suspend fun connect() {
            connects++
            state.value = ConnectionState.CONNECTING
            if (pending) awaitCancellation()
            state.value = ConnectionState.CONNECTED
        }
        override suspend fun disconnect() { disconnects++; state.value = ConnectionState.DISCONNECTED }
        override suspend fun send(message: BenshiMessage) { sent.add(message); yield() }
    }

    private class FakeAudio(val canStart: Boolean = true, val startup: CompletableDeferred<Unit>? = null) : RadioAudioControl {
        override val isConnected = MutableStateFlow(false)
        override val isTransmitting = MutableStateFlow(false)
        var starts = 0
        var stops = 0
        val events = mutableListOf<String>()
        override suspend fun connect() { isConnected.value = true }
        override suspend fun disconnect() { events.add("close"); isConnected.value = false; isTransmitting.value = false }
        override suspend fun startTransmit(): Boolean {
            starts++
            startup?.await()
            events.add("start")
            isTransmitting.value = canStart
            return canStart
        }
        override suspend fun stopTransmit() { stops++; events.add("stop"); isTransmitting.value = false }
    }
    private class FakeFocus : AudioFocusControl {
        var held = false
        var loss: (() -> Unit)? = null
        override fun request(onLoss: () -> Unit): Boolean { held = true; loss = onLoss; return true }
        override fun abandon() { held = false }
    }

    @Test fun disconnectDuringTransmitStopsBeforeClosingAudio() = runTest {
        val audio = FakeAudio()
        val controller = RadioController(FakeTransport(), factory, context, backgroundScope, FakeFocus()) { audio }
        controller.connect(); runCurrent(); advanceTimeBy(1001); runCurrent()
        controller.pttDown(); runCurrent()
        audio.events.clear()
        controller.disconnect(); runCurrent()
        assertEquals("stop", audio.events.first())
        assertTrue(audio.events.indexOf("stop") < audio.events.indexOf("close"))
        assertFalse(controller.isPttActive.value)
    }

    @Test fun switchDuringTransmitStopsOldAudioBeforeClose() = runTest {
        val old = FakeTransport()
        val next = FakeTransport()
        val audio = FakeAudio()
        every { factory.create("next", false) } returns next
        val controller = RadioController(old, factory, context, backgroundScope, FakeFocus()) { target -> if (target === old) audio else null }
        controller.connect(); runCurrent(); advanceTimeBy(1001); runCurrent()
        controller.pttDown(); runCurrent(); audio.events.clear()
        val switch = launch { controller.switchTransport("next", false) }
        runCurrent(); switch.join()
        assertEquals("stop", audio.events.first())
        assertTrue(audio.events.indexOf("stop") < audio.events.indexOf("close"))
        assertFalse(controller.isPttActive.value)
        controller.disconnect(); runCurrent()
    }

    @Test fun audioAndDataLinkLossStopTransmit() = runTest {
        val radio = FakeTransport()
        val audio = FakeAudio()
        val focus = FakeFocus()
        val controller = RadioController(radio, factory, context, backgroundScope, focus) { audio }
        controller.connect(); runCurrent(); advanceTimeBy(1001); runCurrent()
        controller.pttDown(); runCurrent()
        audio.isConnected.value = false; runCurrent()
        assertFalse(audio.isTransmitting.value)
        assertFalse(focus.held)
        advanceTimeBy(6001); runCurrent()
        controller.pttDown(); runCurrent()
        assertTrue(audio.isTransmitting.value)
        radio.state.value = ConnectionState.ERROR; runCurrent()
        assertFalse(audio.isTransmitting.value)
        assertFalse(controller.isPttActive.value)
        controller.disconnect(); runCurrent()
    }

    @Test fun releaseQueuedWhileCaptureStartsStillStopsCapture() = runTest {
        val ready = CompletableDeferred<Unit>()
        val audio = FakeAudio(startup = ready)
        val controller = RadioController(FakeTransport(), factory, context, backgroundScope, FakeFocus()) { audio }
        controller.connect(); runCurrent(); advanceTimeBy(1001); runCurrent()
        controller.pttDown(); runCurrent()
        controller.pttUp(); runCurrent()
        ready.complete(Unit); runCurrent()
        assertEquals(1, audio.starts)
        assertFalse(controller.isPttActive.value)
        assertFalse(audio.isTransmitting.value)
        controller.disconnect(); runCurrent()
    }

    @Test fun pttDuplicatesReleaseAndTimeoutAreBounded() = runTest {
        val radio = FakeTransport()
        val audio = FakeAudio()
        val focus = FakeFocus()
        val controller = RadioController(radio, factory, context, backgroundScope, focus) { audio }
        controller.connect(); runCurrent(); advanceTimeBy(1001); runCurrent()
        controller.pttDown(); controller.pttDown(); runCurrent()
        assertTrue(controller.isPttActive.value)
        assertEquals(1, audio.starts)
        controller.pttUp(); controller.pttUp(); runCurrent()
        assertFalse(controller.isPttActive.value)
        assertFalse(focus.held)
        controller.pttDown(); runCurrent()
        advanceTimeBy(120001); runCurrent()
        assertFalse(controller.isPttActive.value)
        assertFalse(audio.isTransmitting.value)
        assertFalse(focus.held)
        controller.disconnect(); runCurrent()
    }

    @Test fun captureFailureDoesNotLeaveTransmitOrFocusActive() = runTest {
        val audio = FakeAudio(false)
        val focus = FakeFocus()
        val controller = RadioController(FakeTransport(), factory, context, backgroundScope, focus) { audio }
        controller.connect(); runCurrent(); advanceTimeBy(1001); runCurrent()
        controller.pttDown(); runCurrent()
        assertFalse(controller.isPttActive.value)
        assertFalse(focus.held)
        controller.disconnect(); runCurrent()
    }

    @Test fun focusLossStopsCaptureAndDisconnectNeverResumesIt() = runTest {
        val audio = FakeAudio()
        val focus = FakeFocus()
        val controller = RadioController(FakeTransport(), factory, context, backgroundScope, focus) { audio }
        controller.connect(); runCurrent(); advanceTimeBy(1001); runCurrent()
        controller.pttDown(); runCurrent()
        focus.loss?.invoke(); runCurrent()
        assertFalse(audio.isTransmitting.value)
        controller.pttDown(); runCurrent()
        controller.disconnect(); runCurrent()
        assertFalse(audio.isTransmitting.value)
        assertFalse(controller.isPttActive.value)
        assertFalse(audio.isConnected.value)
    }

    @Test fun repeatedConnectStartsOneSession() = runTest {
        val radio = FakeTransport()
        val controller = RadioController(radio, factory, context, backgroundScope)
        controller.connect(); controller.connect(); runCurrent()
        assertEquals(1, radio.connects)
        assertEquals(ConnectionState.CONNECTED, controller.connectionState.value)
        controller.disconnect(); runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, controller.connectionState.value)
        advanceTimeBy(10000); runCurrent()
        assertEquals(1, radio.connects)
    }

    @Test fun disconnectCancelsPendingConnectWithoutRetry() = runTest {
        val radio = FakeTransport(pending = true)
        val controller = RadioController(radio, factory, context, backgroundScope)
        controller.connect(); runCurrent()
        controller.disconnect(); runCurrent()
        assertTrue(radio.disconnects > 0)
        assertEquals(ConnectionState.DISCONNECTED, controller.connectionState.value)
        advanceTimeBy(30000); runCurrent()
        assertEquals(1, radio.connects)
    }

    @Test fun swapKeepsObservableAndClosesOldSession() = runTest {
        val old = FakeTransport()
        val replacement = FakeTransport()
        every { factory.create("new", false) } returns replacement
        val controller = RadioController(old, factory, context, backgroundScope)
        val observed = controller.connectionState
        controller.connect(); runCurrent()
        val swap = launch { controller.switchTransport("new", false) }
        runCurrent(); swap.join()
        assertSame(observed, controller.connectionState)
        assertTrue(old.disconnects > 0)
        assertEquals(1, replacement.connects)
        assertEquals(ConnectionState.CONNECTED, observed.value)
        old.state.value = ConnectionState.ERROR
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, observed.value)
        controller.disconnect(); runCurrent()
    }

    @Test fun writeAndStoreStayAdjacentAcrossConcurrentRequests() = runTest {
        val radio = FakeTransport()
        val controller = RadioController(radio, factory, context, backgroundScope)
        controller.connect(); runCurrent()
        controller.saveChannel(RfChannel(channelId = 1, name = "TEST1", txFreqHz = 146520000, rxFreqHz = 146520000))
        controller.setVolume(3)
        controller.saveChannel(RfChannel(channelId = 2, name = "TEST2", txFreqHz = 146520000, rxFreqHz = 146520000))
        runCurrent()
        assertEquals(listOf(BasicCommand.WRITE_RF_CH, BasicCommand.STORE_SETTINGS,
            BasicCommand.SET_VOLUME, BasicCommand.WRITE_RF_CH, BasicCommand.STORE_SETTINGS), radio.sent.map { it.command })
        controller.disconnect(); runCurrent()
    }

    @Test fun scanDoesNotToggleWhenAlreadyOffAndCoalescesRepeatedOn() = runTest {
        val radio = FakeTransport()
        val controller = RadioController(radio, factory, context, backgroundScope)
        controller.connect(); runCurrent()
        radio.messages.emit(BenshiMessage(CommandGroup.BASIC, BasicCommand.GET_HT_STATUS, true, byteArrayOf(0, 0, 0, 0, 0)))
        runCurrent()
        controller.setScan(false); runCurrent()
        assertTrue(radio.sent.isEmpty())
        controller.setScan(true); controller.setScan(true); runCurrent()
        assertEquals(1, radio.sent.count { it.command == BasicCommand.DO_PROG_FUNC })
        controller.disconnect(); runCurrent()
    }

    @Test fun pttWithoutAudioDoesNotSendToggleOrClaimTransmit() = runTest {
        val radio = FakeTransport()
        val controller = RadioController(radio, factory, context, backgroundScope)
        controller.connect(); runCurrent()
        controller.pttDown(); controller.pttDown(); controller.pttUp(); runCurrent()
        assertFalse(controller.isPttActive.value)
        assertTrue(radio.sent.isEmpty())
        controller.disconnect(); runCurrent()
    }
}
