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

/**
 * Health of the local media **send** (uplink) path.
 *
 * Distinct from [Room.State] when the server uses `subscriberPrimary`:
 * the room may report [Room.State.CONNECTED] once the subscriber transport is
 * up, while local audio/video is still negotiating or failed on the publisher
 * transport.
 *
 * ## Example
 * ```kotlin
 * room.events.collect { event ->
 *     if (event is RoomEvent.MediaSendConnectionStateChanged) {
 *         when {
 *             event.state.isRoomRecovering -> showRoomRecovering()
 *             event.state.isMediaSendAbnormal -> showMediaSendIssue()
 *             // CONNECTING is normal publisher negotiation and should not warn.
 *         }
 *     }
 * }
 * ```
 */
enum class MediaSendConnectionState {
    /** No local media is currently being sent. */
    IDLE,

    /** Local media is being published and the publisher transport is negotiating. */
    CONNECTING,

    /** The publisher transport is connected; local media can be sent. */
    CONNECTED,

    /** The publisher transport is temporarily unhealthy and the SDK is recovering. */
    RECOVERING,

    /** The publisher transport failed and local media cannot be sent. */
    FAILED,

    /** The room's primary connection is temporarily unhealthy and the SDK is recovering. */
    ROOM_RECOVERING,
    ;

    /**
     * `true` when the whole room connection is recovering.
     */
    val isRoomRecovering: Boolean
        get() = this == ROOM_RECOVERING

    /**
     * `true` when only the publisher uplink is recovering or failed.
     */
    val isMediaSendAbnormal: Boolean
        get() = this == RECOVERING || this == FAILED

    /**
     * `true` when the uplink is degraded while the room may still receive remote media.
     */
    val isAbnormal: Boolean
        get() = isMediaSendAbnormal

    /**
     * `true` when the uplink is not yet ready or is unhealthy.
     */
    val isDegraded: Boolean
        get() = this == CONNECTING || this == ROOM_RECOVERING || isMediaSendAbnormal

    companion object {
        /**
         * Pure state-machine used by [Room] (and unit tests) to derive uplink health.
         *
         * @param roomState current [Room.state]
         * @param subscriberPrimary whether the server is using subscriber-primary mode
         * @param hasPublished whether the client has attempted to publish / negotiate the publisher PC
         * @param isResumingOrReconnecting whether the engine is soft-resuming or hard-reconnecting
         * @param publisherConnectionState latest publisher PeerConnection state, or null if unknown
         */
        @JvmStatic
        fun compute(
            roomState: Room.State,
            subscriberPrimary: Boolean,
            hasPublished: Boolean,
            isResumingOrReconnecting: Boolean,
            publisherConnectionState: PeerConnection.PeerConnectionState?,
        ): MediaSendConnectionState = computeFull(
            roomState = roomState,
            subscriberPrimary = subscriberPrimary,
            hasPublished = hasPublished,
            isResumingOrReconnecting = isResumingOrReconnecting,
            publisherConnectionState = publisherConnectionState,
            isWholeConnectionRecovering = false,
            hasPublisherEverConnected = true,
        )

        internal fun computeFull(
            roomState: Room.State,
            subscriberPrimary: Boolean,
            hasPublished: Boolean,
            isResumingOrReconnecting: Boolean,
            publisherConnectionState: PeerConnection.PeerConnectionState?,
            isWholeConnectionRecovering: Boolean,
            hasPublisherEverConnected: Boolean,
        ): MediaSendConnectionState {
            if (roomState == Room.State.DISCONNECTED) {
                return IDLE
            }

            if (isWholeConnectionRecovering ||
                isResumingOrReconnecting ||
                roomState == Room.State.RECONNECTING
            ) {
                return ROOM_RECOVERING
            }

            if (!hasPublished && subscriberPrimary) {
                return IDLE
            }

            if (!subscriberPrimary) {
                return when {
                    roomState == Room.State.CONNECTED -> CONNECTED
                    roomState == Room.State.CONNECTING && hasPublished -> CONNECTING
                    else -> IDLE
                }
            }

            // subscriberPrimary
            if (roomState != Room.State.CONNECTED && roomState != Room.State.RECONNECTING) {
                if (roomState == Room.State.CONNECTING && hasPublished) {
                    return CONNECTING
                }
                return IDLE
            }

            return when (publisherConnectionState) {
                null -> CONNECTING
                PeerConnection.PeerConnectionState.CONNECTED -> CONNECTED
                PeerConnection.PeerConnectionState.CONNECTING,
                PeerConnection.PeerConnectionState.NEW,
                -> CONNECTING
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED,
                -> FAILED
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    if (hasPublisherEverConnected) RECOVERING else CONNECTING
                }
            }
        }
    }
}

internal inline fun applyMediaSendConnectionStateTransition(
    current: MediaSendConnectionState,
    next: MediaSendConnectionState,
    updateState: (MediaSendConnectionState) -> Unit,
    emitEvent: (new: MediaSendConnectionState, old: MediaSendConnectionState) -> Unit,
) {
    if (current == next) {
        return
    }
    updateState(next)
    emitEvent(next, current)
}
