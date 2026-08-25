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

import livekit.org.webrtc.PeerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSendConnectionStateTest {

    @Test
    fun enumOrderAndHelpers() {
        assertEquals(
            listOf(
                "IDLE",
                "CONNECTING",
                "CONNECTED",
                "RECOVERING",
                "FAILED",
                "ROOM_RECOVERING",
            ),
            MediaSendConnectionState.entries.map { it.name },
        )

        MediaSendConnectionState.entries.forEach { state ->
            assertEquals(state == MediaSendConnectionState.ROOM_RECOVERING, state.isRoomRecovering)
            assertEquals(
                state == MediaSendConnectionState.RECOVERING ||
                    state == MediaSendConnectionState.FAILED,
                state.isMediaSendAbnormal,
            )
            assertEquals(state.isMediaSendAbnormal, state.isAbnormal)
            assertEquals(
                state == MediaSendConnectionState.CONNECTING ||
                    state == MediaSendConnectionState.ROOM_RECOVERING ||
                    state == MediaSendConnectionState.RECOVERING ||
                    state == MediaSendConnectionState.FAILED,
                state.isDegraded,
            )
        }

        assertFalse(MediaSendConnectionState.CONNECTING.isMediaSendAbnormal)
    }

    @Test
    fun publicFiveArgumentComputeAbiDelegatesToFullStateMachine() {
        assertEquals(
            MediaSendConnectionState.CONNECTING,
            MediaSendConnectionState.compute(
                roomState = Room.State.CONNECTED,
                subscriberPrimary = true,
                hasPublished = true,
                isResumingOrReconnecting = false,
                publisherConnectionState = null,
            ),
        )
        assertEquals(
            MediaSendConnectionState.ROOM_RECOVERING,
            MediaSendConnectionState.compute(
                roomState = Room.State.RECONNECTING,
                subscriberPrimary = true,
                hasPublished = true,
                isResumingOrReconnecting = false,
                publisherConnectionState = PeerConnection.PeerConnectionState.FAILED,
            ),
        )
        assertEquals(
            MediaSendConnectionState.ROOM_RECOVERING,
            MediaSendConnectionState.compute(
                roomState = Room.State.CONNECTED,
                subscriberPrimary = false,
                hasPublished = true,
                isResumingOrReconnecting = true,
                publisherConnectionState = PeerConnection.PeerConnectionState.FAILED,
            ),
        )
    }

    @Test
    fun disconnectedRoomAlwaysIdle() {
        assertEquals(
            MediaSendConnectionState.IDLE,
            computeFull(
                roomState = Room.State.DISCONNECTED,
                subscriberPrimary = true,
                hasPublished = true,
                isWholeConnectionRecovering = true,
                hasPublisherEverConnected = true,
                publisherConnectionState = PeerConnection.PeerConnectionState.FAILED,
            ),
        )
    }

    @Test
    fun wholeConnectionRecoveryOverridesPublishAndPublisherState() {
        listOf(false, true).forEach { subscriberPrimary ->
            listOf(false, true).forEach { hasPublished ->
                PeerConnection.PeerConnectionState.entries.forEach { publisherState ->
                    assertEquals(
                        "subscriberPrimary=$subscriberPrimary hasPublished=$hasPublished publisher=$publisherState",
                        MediaSendConnectionState.ROOM_RECOVERING,
                        computeFull(
                            subscriberPrimary = subscriberPrimary,
                            hasPublished = hasPublished,
                            isWholeConnectionRecovering = true,
                            hasPublisherEverConnected = true,
                            publisherConnectionState = publisherState,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun noPublishIsIdleWhenRoomStable() {
        assertEquals(
            MediaSendConnectionState.IDLE,
            computeFull(
                subscriberPrimary = true,
                hasPublished = false,
                isWholeConnectionRecovering = false,
                hasPublisherEverConnected = false,
                publisherConnectionState = PeerConnection.PeerConnectionState.FAILED,
            ),
        )
    }

    @Test
    fun subscriberPrimaryBeforeFirstPublisherConnection() {
        val connectingStates = listOf(
            null,
            PeerConnection.PeerConnectionState.NEW,
            PeerConnection.PeerConnectionState.CONNECTING,
            PeerConnection.PeerConnectionState.DISCONNECTED,
        )
        connectingStates.forEach { publisherState ->
            assertEquals(
                "publisher=$publisherState",
                MediaSendConnectionState.CONNECTING,
                computeFull(
                    subscriberPrimary = true,
                    hasPublished = true,
                    hasPublisherEverConnected = false,
                    publisherConnectionState = publisherState,
                ),
            )
        }

        listOf(
            PeerConnection.PeerConnectionState.FAILED,
            PeerConnection.PeerConnectionState.CLOSED,
        ).forEach { publisherState ->
            assertEquals(
                "publisher=$publisherState",
                MediaSendConnectionState.FAILED,
                computeFull(
                    subscriberPrimary = true,
                    hasPublished = true,
                    hasPublisherEverConnected = false,
                    publisherConnectionState = publisherState,
                ),
            )
        }

        assertEquals(
            MediaSendConnectionState.CONNECTED,
            computeFull(
                subscriberPrimary = true,
                hasPublished = true,
                hasPublisherEverConnected = false,
                publisherConnectionState = PeerConnection.PeerConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun subscriberPrimaryAfterPublisherHasConnected() {
        val expectedByPublisherState = mapOf(
            null to MediaSendConnectionState.CONNECTING,
            PeerConnection.PeerConnectionState.NEW to MediaSendConnectionState.CONNECTING,
            PeerConnection.PeerConnectionState.CONNECTING to MediaSendConnectionState.CONNECTING,
            PeerConnection.PeerConnectionState.CONNECTED to MediaSendConnectionState.CONNECTED,
            PeerConnection.PeerConnectionState.DISCONNECTED to MediaSendConnectionState.RECOVERING,
            PeerConnection.PeerConnectionState.FAILED to MediaSendConnectionState.FAILED,
            PeerConnection.PeerConnectionState.CLOSED to MediaSendConnectionState.FAILED,
        )

        expectedByPublisherState.forEach { (publisherState, expected) ->
            assertEquals(
                "publisher=$publisherState",
                expected,
                computeFull(
                    subscriberPrimary = true,
                    hasPublished = true,
                    hasPublisherEverConnected = true,
                    publisherConnectionState = publisherState,
                ),
            )
        }
    }

    @Test
    fun nonSubscriberPrimaryUsesRoomAndWholeRecoveryState() {
        assertEquals(
            MediaSendConnectionState.CONNECTED,
            computeFull(
                subscriberPrimary = false,
                hasPublished = false,
                isWholeConnectionRecovering = false,
                hasPublisherEverConnected = false,
                publisherConnectionState = null,
            ),
        )
        assertEquals(
            MediaSendConnectionState.ROOM_RECOVERING,
            computeFull(
                subscriberPrimary = false,
                hasPublished = true,
                isWholeConnectionRecovering = true,
                hasPublisherEverConnected = true,
                publisherConnectionState = PeerConnection.PeerConnectionState.FAILED,
            ),
        )
        assertEquals(
            MediaSendConnectionState.CONNECTING,
            computeFull(
                roomState = Room.State.CONNECTING,
                subscriberPrimary = false,
                hasPublished = true,
                isWholeConnectionRecovering = false,
                hasPublisherEverConnected = false,
                publisherConnectionState = PeerConnection.PeerConnectionState.CONNECTING,
            ),
        )
        assertEquals(
            MediaSendConnectionState.IDLE,
            computeFull(
                roomState = Room.State.CONNECTING,
                subscriberPrimary = false,
                hasPublished = false,
                isWholeConnectionRecovering = false,
                hasPublisherEverConnected = false,
                publisherConnectionState = null,
            ),
        )
    }

    @Test
    fun lookupSemanticsDoNotWarnForConnecting() {
        fun shouldWarn(room: Room.State, send: MediaSendConnectionState): Boolean {
            return room == Room.State.CONNECTED &&
                (send.isRoomRecovering || send.isMediaSendAbnormal)
        }

        assertFalse(shouldWarn(Room.State.CONNECTED, MediaSendConnectionState.IDLE))
        assertFalse(shouldWarn(Room.State.CONNECTED, MediaSendConnectionState.CONNECTING))
        assertFalse(shouldWarn(Room.State.CONNECTED, MediaSendConnectionState.CONNECTED))
        assertTrue(shouldWarn(Room.State.CONNECTED, MediaSendConnectionState.ROOM_RECOVERING))
        assertTrue(shouldWarn(Room.State.CONNECTED, MediaSendConnectionState.RECOVERING))
        assertTrue(shouldWarn(Room.State.CONNECTED, MediaSendConnectionState.FAILED))
        assertFalse(shouldWarn(Room.State.CONNECTING, MediaSendConnectionState.FAILED))
        assertFalse(shouldWarn(Room.State.RECONNECTING, MediaSendConnectionState.ROOM_RECOVERING))
    }

    @Test
    fun wholeRecoveryReducerClearsWithUnknownNetworkAfterStableRecovery() {
        val reducer = WholeConnectionRecoveryReducer()

        assertTrue(
            reducer.reduce(
                recoveryInput(
                    engineState = ConnectionState.DISCONNECTED,
                    networkState = MediaSendNetworkState.UNKNOWN,
                ),
            ),
        )
        assertFalse(
            reducer.reduce(
                recoveryInput(
                    engineState = ConnectionState.CONNECTED,
                    networkState = MediaSendNetworkState.UNKNOWN,
                ),
            ),
        )
    }

    @Test
    fun wholeRecoveryReducerDoesNotClearOnNetworkAvailableBeforeRecoveryRuns() {
        val reducer = WholeConnectionRecoveryReducer()

        assertTrue(
            reducer.reduce(
                recoveryInput(networkState = MediaSendNetworkState.UNAVAILABLE),
            ),
        )
        assertTrue(
            reducer.reduce(
                recoveryInput(networkState = MediaSendNetworkState.AVAILABLE),
            ),
        )
        assertTrue(
            reducer.reduce(
                recoveryInput(networkState = MediaSendNetworkState.UNAVAILABLE),
            ),
        )
        assertTrue(
            reducer.reduce(
                recoveryInput(
                    engineState = ConnectionState.RESUMING,
                    networkState = MediaSendNetworkState.UNAVAILABLE,
                ),
            ),
        )
        assertTrue(
            reducer.reduce(
                recoveryInput(
                    engineState = ConnectionState.CONNECTED,
                    networkState = MediaSendNetworkState.UNAVAILABLE,
                ),
            ),
        )
        assertFalse(
            reducer.reduce(
                recoveryInput(
                    engineState = ConnectionState.CONNECTED,
                    networkState = MediaSendNetworkState.AVAILABLE,
                ),
            ),
        )
    }

    @Test
    fun wholeRecoveryReducerWaitsForPublisherWhenPublished() {
        val reducer = WholeConnectionRecoveryReducer()

        assertTrue(
            reducer.reduce(
                recoveryInput(
                    engineState = ConnectionState.RESUMING,
                    hasPublished = true,
                    publisherConnectionState = PeerConnection.PeerConnectionState.CONNECTED,
                    networkState = MediaSendNetworkState.AVAILABLE,
                ),
            ),
        )
        assertTrue(
            reducer.reduce(
                recoveryInput(
                    engineState = ConnectionState.CONNECTED,
                    hasPublished = true,
                    publisherConnectionState = PeerConnection.PeerConnectionState.DISCONNECTED,
                    networkState = MediaSendNetworkState.AVAILABLE,
                ),
            ),
        )
        assertFalse(
            reducer.reduce(
                recoveryInput(
                    engineState = ConnectionState.CONNECTED,
                    hasPublished = true,
                    publisherConnectionState = PeerConnection.PeerConnectionState.CONNECTED,
                    networkState = MediaSendNetworkState.AVAILABLE,
                ),
            ),
        )
    }

    @Test
    fun wholeRecoveryReducerDisconnectResetsRecoveryEpoch() {
        val reducer = WholeConnectionRecoveryReducer()

        assertTrue(
            reducer.reduce(
                recoveryInput(engineState = ConnectionState.RECONNECTING),
            ),
        )
        assertFalse(
            reducer.reduce(
                recoveryInput(
                    roomState = Room.State.DISCONNECTED,
                    engineState = ConnectionState.RECONNECTING,
                ),
            ),
        )
        assertFalse(
            reducer.reduce(
                recoveryInput(
                    roomState = Room.State.CONNECTING,
                    engineState = ConnectionState.DISCONNECTED,
                ),
            ),
        )
    }

    @Test
    fun rapidSerializedTransitionsEmitExactOldNewSequence() {
        var current = MediaSendConnectionState.IDLE
        val events = mutableListOf<Pair<MediaSendConnectionState, MediaSendConnectionState>>()

        fun transition(next: MediaSendConnectionState) {
            applyMediaSendConnectionStateTransition(
                current = current,
                next = next,
                updateState = { current = it },
                emitEvent = { new, old -> events += old to new },
            )
        }

        transition(MediaSendConnectionState.CONNECTING)
        transition(MediaSendConnectionState.ROOM_RECOVERING)
        transition(MediaSendConnectionState.CONNECTED)
        transition(MediaSendConnectionState.IDLE)
        transition(MediaSendConnectionState.IDLE)

        assertEquals(MediaSendConnectionState.IDLE, current)
        assertEquals(
            listOf(
                MediaSendConnectionState.IDLE to MediaSendConnectionState.CONNECTING,
                MediaSendConnectionState.CONNECTING to MediaSendConnectionState.ROOM_RECOVERING,
                MediaSendConnectionState.ROOM_RECOVERING to MediaSendConnectionState.CONNECTED,
                MediaSendConnectionState.CONNECTED to MediaSendConnectionState.IDLE,
            ),
            events,
        )
    }

    private fun recoveryInput(
        roomState: Room.State = Room.State.CONNECTED,
        engineState: ConnectionState = ConnectionState.CONNECTED,
        hasPublished: Boolean = false,
        publisherConnectionState: PeerConnection.PeerConnectionState? = null,
        networkState: MediaSendNetworkState = MediaSendNetworkState.UNKNOWN,
    ): WholeConnectionRecoveryInput {
        return WholeConnectionRecoveryInput(
            roomState = roomState,
            engineState = engineState,
            hasPublished = hasPublished,
            publisherConnectionState = publisherConnectionState,
            networkState = networkState,
        )
    }

    private fun computeFull(
        roomState: Room.State = Room.State.CONNECTED,
        subscriberPrimary: Boolean,
        hasPublished: Boolean,
        isWholeConnectionRecovering: Boolean = false,
        hasPublisherEverConnected: Boolean,
        publisherConnectionState: PeerConnection.PeerConnectionState?,
    ): MediaSendConnectionState {
        return MediaSendConnectionState.computeFull(
            roomState = roomState,
            subscriberPrimary = subscriberPrimary,
            hasPublished = hasPublished,
            isResumingOrReconnecting = false,
            publisherConnectionState = publisherConnectionState,
            isWholeConnectionRecovering = isWholeConnectionRecovering,
            hasPublisherEverConnected = hasPublisherEverConnected,
        )
    }
}
