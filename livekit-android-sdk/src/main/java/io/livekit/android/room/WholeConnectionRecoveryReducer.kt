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

internal enum class MediaSendNetworkState {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE,
}

internal data class WholeConnectionRecoveryInput(
    val roomState: Room.State,
    val engineState: ConnectionState,
    val hasPublished: Boolean,
    val publisherConnectionState: PeerConnection.PeerConnectionState?,
    val networkState: MediaSendNetworkState,
)

internal class WholeConnectionRecoveryReducer {
    private var isRecovering = false
    private var hasObservedConnectionRecovery = false

    fun reduce(input: WholeConnectionRecoveryInput): Boolean {
        if (input.roomState == Room.State.DISCONNECTED) {
            reset()
            return false
        }

        val connectionRecovering =
            input.roomState == Room.State.RECONNECTING ||
                input.engineState == ConnectionState.RECONNECTING ||
                input.engineState == ConnectionState.RESUMING ||
                (input.roomState == Room.State.CONNECTED &&
                    input.engineState == ConnectionState.DISCONNECTED)
        if (connectionRecovering) {
            hasObservedConnectionRecovery = true
        }

        if (connectionRecovering || input.networkState == MediaSendNetworkState.UNAVAILABLE) {
            isRecovering = true
            return true
        }

        if (!isRecovering) {
            return false
        }

        val isStable =
            input.roomState == Room.State.CONNECTED &&
                input.engineState == ConnectionState.CONNECTED &&
                (!input.hasPublished ||
                    input.publisherConnectionState == PeerConnection.PeerConnectionState.CONNECTED)
        if (hasObservedConnectionRecovery && isStable) {
            reset()
        }
        return isRecovering
    }

    fun reset() {
        isRecovering = false
        hasObservedConnectionRecovery = false
    }
}
