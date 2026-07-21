/*
 * Copyright 2023-2026 LiveKit, Inc.
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

package io.livekit.android.composesample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.livekit.android.composesample.ui.VideoRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.sample.common.R
import io.livekit.android.util.flow

/**
 * This widget primarily serves as a way to observe changes in [Participant.videoTrackPublications].
 */
@Composable
fun VideoItemTrackSelector(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
) {
    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs = videoTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }

    val screenSharePub = videoPubs.firstOrNull { pub -> pub.source == Track.Source.SCREEN_SHARE }
    val cameraPub = videoPubs.firstOrNull { pub -> pub.source == Track.Source.CAMERA }
    val fallbackPub = videoPubs.firstOrNull()
    val mainVideoPub = screenSharePub ?: cameraPub ?: fallbackPub

    val videoTrack = mainVideoPub?.track as? VideoTrack
    val cameraTrack = if (screenSharePub != null) {
        cameraPub?.track as? VideoTrack
    } else {
        null
    }
    var videoMuted by remember { mutableStateOf(false) }
    var cameraFacingFront by remember { mutableStateOf(false) }

    // monitor muted state
    LaunchedEffect(mainVideoPub) {
        if (mainVideoPub != null) {
            mainVideoPub::muted.flow.collect { muted -> videoMuted = muted }
        }
    }

    // monitor camera facing for local participant
    LaunchedEffect(participant, videoTrack) {
        if (room.localParticipant == participant && videoTrack as? LocalVideoTrack != null) {
            videoTrack::options.flow.collect { options ->
                cameraFacingFront = options.position == CameraPosition.FRONT
            }
        }
    }

    Box(modifier = modifier) {
        if (videoTrack != null && (!videoMuted || mainVideoPub?.source == Track.Source.CAMERA)) {
            VideoRenderer(
                room = room,
                videoTrack = videoTrack,
                mirror = room.localParticipant == participant && cameraFacingFront,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(id = R.drawable.outline_videocam_off_24),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (cameraTrack != null) {
            VideoRenderer(
                room = room,
                videoTrack = cameraTrack,
                mirror = room.localParticipant == participant && cameraFacingFront,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(96.dp),
            )
        }
    }
}
