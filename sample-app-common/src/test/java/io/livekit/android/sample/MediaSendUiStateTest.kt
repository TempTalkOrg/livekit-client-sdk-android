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

package io.livekit.android.sample

import io.livekit.android.room.MediaSendConnectionState
import io.livekit.android.room.Room
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSendUiStateTest {
    @Test
    fun `room reconnecting takes precedence over media state`() {
        assertEquals(
            MediaSendUiState.ROOM_RECOVERING,
            resolveMediaSendUiState(Room.State.RECONNECTING, MediaSendConnectionState.FAILED),
        )
    }

    @Test
    fun `room recovering media state uses connection presentation`() {
        assertEquals(
            MediaSendUiState.ROOM_RECOVERING,
            resolveMediaSendUiState(Room.State.CONNECTED, MediaSendConnectionState.ROOM_RECOVERING),
        )
    }

    @Test
    fun `disconnected room takes precedence over room recovering media state`() {
        assertEquals(
            MediaSendUiState.NONE,
            resolveMediaSendUiState(Room.State.DISCONNECTED, MediaSendConnectionState.ROOM_RECOVERING),
        )
    }

    @Test
    fun `connected room shows only abnormal media states`() {
        assertEquals(
            MediaSendUiState.MEDIA_RECOVERING,
            resolveMediaSendUiState(Room.State.CONNECTED, MediaSendConnectionState.RECOVERING),
        )
        assertEquals(
            MediaSendUiState.MEDIA_RECOVERING,
            resolveMediaSendUiState(Room.State.CONNECTED, MediaSendConnectionState.FAILED),
        )
        assertEquals(
            MediaSendUiState.NONE,
            resolveMediaSendUiState(Room.State.CONNECTED, MediaSendConnectionState.CONNECTING),
        )
        assertEquals(
            MediaSendUiState.NONE,
            resolveMediaSendUiState(Room.State.CONNECTED, MediaSendConnectionState.IDLE),
        )
        assertEquals(
            MediaSendUiState.NONE,
            resolveMediaSendUiState(Room.State.CONNECTED, MediaSendConnectionState.CONNECTED),
        )
    }

    @Test
    fun `non-connected room hides media-only warnings`() {
        assertEquals(
            MediaSendUiState.NONE,
            resolveMediaSendUiState(Room.State.CONNECTING, MediaSendConnectionState.RECOVERING),
        )
        assertEquals(
            MediaSendUiState.NONE,
            resolveMediaSendUiState(Room.State.DISCONNECTED, MediaSendConnectionState.FAILED),
        )
    }
}
