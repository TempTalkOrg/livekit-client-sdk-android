/*
 * Copyright 2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.room

import io.livekit.android.events.RoomEvent
import io.livekit.android.test.MockE2ETest
import io.livekit.android.test.events.EventCollector
import io.livekit.android.test.events.FlowCollector
import io.livekit.android.test.mock.TestData
import io.livekit.android.util.flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import livekit.LivekitRtc
import livekit.org.webrtc.PeerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class RoomInitialConnectMockE2ETest : MockE2ETest() {

    private val fastPublishJoin: LivekitRtc.SignalResponse = TestData.JOIN.toBuilder()
        .setJoin(TestData.JOIN.join.toBuilder().setFastPublish(true).build())
        .build()

    private val publisherPrimaryJoin: LivekitRtc.SignalResponse = TestData.JOIN.toBuilder()
        .setJoin(TestData.JOIN.join.toBuilder().setSubscriberPrimary(false).build())
        .build()

    private fun leaveWithAction(action: LivekitRtc.LeaveRequest.Action): LivekitRtc.SignalResponse =
        TestData.LEAVE.toBuilder()
            .setLeave(TestData.LEAVE.leave.toBuilder().setAction(action).build())
            .build()

    private fun launchConnect(): Deferred<Throwable?> = coroutineRule.scope.async {
        try {
            room.connect(url = TestData.EXAMPLE_URL, token = "token")
            null
        } catch (e: Throwable) {
            e
        }
    }

    @Test
    fun connectWaitsForSubscriberBeforeConnected() = runTest {
        val events = EventCollector(room.events, coroutineRule.scope)
        val result = launchConnect()
        prepareSignal()
        simulateMessageFromServer(TestData.OFFER)
        runCurrent()

        assertEquals(Room.State.CONNECTING, room.state)

        getSubscriberPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(100)
        runCurrent()

        assertNull(result.await())
        assertEquals(Room.State.CONNECTED, room.state)
        assertEquals(1, events.stopCollecting().count { it is RoomEvent.Connected })
    }

    @Test
    fun subscriberTimeoutThrowsMediaUnreachable() = runTest {
        val result = launchConnect()
        // No server offer: MockPeerConnection reaches ICE CONNECTED on its own once an offer is answered.
        prepareSignal()
        advanceTimeBy(15_100)
        runCurrent()

        val error = result.await()
        assertTrue("expected MediaUnreachableException, got $error", error is RoomException.MediaUnreachableException)
        error as RoomException.MediaUnreachableException
        assertEquals("subscriber", error.transport)
        assertFalse(error.failed)
        assertEquals(Room.State.DISCONNECTED, room.state)
    }

    @Test
    fun subscriberFailedThrowsImmediatelyWithoutReconnect() = runTest {
        val events = EventCollector(room.events, coroutineRule.scope)
        val result = launchConnect()
        prepareSignal()
        simulateMessageFromServer(TestData.OFFER)
        runCurrent()

        getSubscriberPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        getSubscriberPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.FAILED)
        advanceTimeBy(100)
        runCurrent()

        val error = result.await() as RoomException.MediaUnreachableException
        assertEquals("subscriber", error.transport)
        assertTrue(error.failed)
        val collected = events.stopCollecting()
        assertTrue(collected.none { it is RoomEvent.Reconnecting })
        assertTrue(collected.none { it is RoomEvent.Connected })
    }

    @Test
    fun publisherTimeoutThrowsMediaUnreachableWhenFastPublish() = runTest {
        val result = launchConnect()
        prepareSignal(fastPublishJoin)
        simulateMessageFromServer(TestData.OFFER)
        runCurrent()
        getSubscriberPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)

        advanceTimeBy(15_100)
        runCurrent()

        val error = result.await() as RoomException.MediaUnreachableException
        assertEquals("publisher", error.transport)
        assertFalse(error.failed)
    }

    @Test
    fun publisherConnectedCompletesConnectWhenFastPublish() = runTest {
        val result = launchConnect()
        prepareSignal(fastPublishJoin)
        simulateMessageFromServer(TestData.OFFER)
        runCurrent()
        getSubscriberPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        getPublisherPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(100)
        runCurrent()

        assertNull(result.await())
        assertEquals(Room.State.CONNECTED, room.state)
    }

    @Test
    fun signalLostDuringWaitFailsConnect() = runTest {
        val events = EventCollector(room.events, coroutineRule.scope)
        val result = launchConnect()
        prepareSignal()
        runCurrent()

        wsFactory.listener.onClosed(wsFactory.ws, 1001, "server going away")
        simulateMessageFromServer(TestData.OFFER)
        getSubscriberPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(100)
        runCurrent()

        val error = result.await()
        assertTrue("expected ConnectException, got $error", error is RoomException.ConnectException)
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertTrue(events.stopCollecting().none { it is RoomEvent.Connected })
    }

    @Test
    fun leaveResumeDuringWaitFailsConnectWithoutReconnect() = runTest {
        assertLeaveDuringWaitFailsConnectWithoutReconnect(LivekitRtc.LeaveRequest.Action.RESUME)
    }

    @Test
    fun leaveReconnectDuringWaitFailsConnectWithoutReconnect() = runTest {
        assertLeaveDuringWaitFailsConnectWithoutReconnect(LivekitRtc.LeaveRequest.Action.RECONNECT)
    }

    private suspend fun TestScope.assertLeaveDuringWaitFailsConnectWithoutReconnect(action: LivekitRtc.LeaveRequest.Action) {
        val events = EventCollector(room.events, coroutineRule.scope)
        val engineStates = FlowCollector(component.rtcEngine()::connectionState.flow, coroutineRule.scope)
        val result = launchConnect()
        prepareSignal()
        runCurrent()

        simulateMessageFromServer(leaveWithAction(action))
        advanceTimeBy(100)
        runCurrent()

        val error = result.await()
        assertTrue("expected ConnectException, got $error", error is RoomException.ConnectException)
        assertEquals(Room.State.DISCONNECTED, room.state)
        val collected = events.stopCollecting()
        assertTrue(collected.none { it is RoomEvent.Reconnecting || it is RoomEvent.Connected })
        val engineCollected = engineStates.stopCollecting()
        assertTrue(
            "unexpected engine states $engineCollected",
            engineCollected.none { it == ConnectionState.RESUMING || it == ConnectionState.RECONNECTING },
        )
    }

    @Test
    fun leaveDisconnectDuringWaitCancelsConnect() = runTest {
        val events = EventCollector(room.events, coroutineRule.scope)
        val result = launchConnect()
        prepareSignal()
        runCurrent()

        simulateMessageFromServer(leaveWithAction(LivekitRtc.LeaveRequest.Action.DISCONNECT))
        advanceTimeBy(100)
        runCurrent()

        val error = result.await()
        assertTrue("expected CancellationException, got $error", error is CancellationException)
        assertFalse(error is RoomException.MediaUnreachableException)
        assertEquals(Room.State.DISCONNECTED, room.state)
        assertTrue(events.stopCollecting().none { it is RoomEvent.Connected || it is RoomEvent.Reconnecting })
    }

    @Test
    fun publisherPrimaryConnectsWithoutSubscriber() = runTest {
        val result = launchConnect()
        prepareSignal(publisherPrimaryJoin)
        runCurrent()
        getPublisherPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(100)
        runCurrent()

        assertNull(result.await())
        assertEquals(Room.State.CONNECTED, room.state)
    }

    @Test
    fun publisherPrimaryTimeoutReportsPublisher() = runTest {
        val result = launchConnect()
        prepareSignal(publisherPrimaryJoin)
        advanceTimeBy(15_100)
        runCurrent()

        val error = result.await() as RoomException.MediaUnreachableException
        assertEquals("publisher", error.transport)
        assertFalse(error.failed)
    }

    @Test
    fun registrationNetworkCallbacksDoNotReconnectAfterInitialConnect() = runTest {
        val registry = component.networkCallbackRegistry()
        registry.notifyOnRegistration = false
        val network = registry.defaultNetwork
        val events = EventCollector(room.events, coroutineRule.scope)
        val roomStates = FlowCollector(room::state.flow, coroutineRule.scope)
        val engineStates = FlowCollector(component.rtcEngine()::connectionState.flow, coroutineRule.scope)

        val result = launchConnect()
        prepareSignal()
        simulateMessageFromServer(TestData.OFFER)
        runCurrent()

        // Device delivery: onAvailable lands while connect waits for the peer connections,
        // onCapabilitiesChanged only after connect has marked the room CONNECTED.
        registry.networkCallbacks.toList().forEach { it.onAvailable(network) }
        getSubscriberPeerConnection().moveToIceConnectionState(PeerConnection.IceConnectionState.CONNECTED)
        advanceTimeBy(100)
        runCurrent()
        assertNull(result.await())
        assertEquals(Room.State.CONNECTED, room.state)

        registry.networkCallbacks.toList().forEach { it.onCapabilitiesChanged(network, registry.createCapabilities()) }
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(events.stopCollecting().none { it is RoomEvent.Reconnecting })
        assertTrue(roomStates.stopCollecting().none { it == Room.State.RECONNECTING })
        val engineCollected = engineStates.stopCollecting()
        assertTrue(
            "unexpected engine states $engineCollected",
            engineCollected.none { it == ConnectionState.RESUMING || it == ConnectionState.RECONNECTING },
        )
        assertEquals(Room.State.CONNECTED, room.state)
    }
}
