/*
 * Copyright 2024-2026 LiveKit, Inc.
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.livekit.android.sample

import android.view.View
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.sample.databinding.ParticipantItemBinding
import io.livekit.android.util.LKLog
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ParticipantItem(
    private val room: Room,
    private val participant: Participant,
    private val speakerView: Boolean = false,
) : BindableItem<ParticipantItemBinding>() {

    private var boundMainVideoTrack: VideoTrack? = null
    private var boundCameraVideoTrack: VideoTrack? = null
    private var coroutineScope: CoroutineScope? = null
    private var initializedBinding: ParticipantItemBinding? = null

    override fun initializeViewBinding(view: View): ParticipantItemBinding {
        return ParticipantItemBinding.bind(view)
    }

    internal fun initializeRenderers(viewBinding: ParticipantItemBinding) {
        if (initializedBinding === viewBinding) {
            return
        }
        initializedBinding?.let(::teardownRenderers)
        room.initVideoRenderer(viewBinding.renderer)
        room.initVideoRenderer(viewBinding.cameraRenderer)
        initializedBinding = viewBinding
    }

    internal fun releaseRenderers(viewBinding: ParticipantItemBinding) {
        if (initializedBinding !== viewBinding) {
            return
        }
        viewBinding.renderer.release()
        viewBinding.cameraRenderer.release()
        initializedBinding = null
    }

    private fun teardownRenderers(viewBinding: ParticipantItemBinding) {
        if (initializedBinding !== viewBinding) {
            return
        }
        boundMainVideoTrack?.removeRenderer(viewBinding.renderer)
        boundCameraVideoTrack?.removeRenderer(viewBinding.cameraRenderer)
        boundMainVideoTrack = null
        boundCameraVideoTrack = null
        releaseRenderers(viewBinding)
    }

    private fun ensureCoroutineScope() {
        if (coroutineScope == null) {
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        }
    }

    override fun bind(viewBinding: ParticipantItemBinding, position: Int) {
        initializeRenderers(viewBinding)
        ensureCoroutineScope()
        coroutineScope?.launch {
            participant::identity.flow.collect { identity ->
                viewBinding.identityText.text = identity?.value
            }
        }
        coroutineScope?.launch {
            participant::isSpeaking.flow.collect { isSpeaking ->
                if (isSpeaking) {
                    showFocus(viewBinding)
                } else {
                    hideFocus(viewBinding)
                }
            }
        }
        coroutineScope?.launch {
            participant::isMicrophoneEnabled.flow
                .collect { isMicEnabled ->
                    viewBinding.muteIndicator.visibility = if (!isMicEnabled) View.VISIBLE else View.INVISIBLE
                }
        }
        coroutineScope?.launch {
            participant::connectionQuality.flow
                .collect { quality ->
                    viewBinding.connectionQuality.visibility =
                        if (quality == ConnectionQuality.POOR) View.VISIBLE else View.INVISIBLE
                }
        }

        // observe videoTracks changes.
        val videoTrackPubFlow = participant::videoTrackPublications.flow
            .map { participant to it }
            .flatMapLatest { (participant, videoTracks) ->
                val screenSharePublication = participant.getTrackPublication(Track.Source.SCREEN_SHARE)
                val cameraPublication = participant.getTrackPublication(Track.Source.CAMERA)
                    ?: videoTracks.firstOrNull { (publication) -> publication.source != Track.Source.SCREEN_SHARE }?.first

                flowOf(VideoPublications(screenShare = screenSharePublication, camera = cameraPublication))
            }

        coroutineScope?.launch {
            val mainVideoTrackFlow = videoTrackPubFlow
                .map { publications -> publications.screenShare ?: publications.camera }
                .flatMapLatestOrNull { publication -> publication::track.flow }
            val cameraVideoTrackFlow = videoTrackPubFlow
                .map { publications ->
                    if (publications.screenShare != null && publications.camera != null) {
                        publications.camera
                    } else {
                        null
                    }
                }
                .flatMapLatestOrNull { publication -> publication::track.flow }

            // Configure video view with track
            launch {
                mainVideoTrackFlow.collectLatest { videoTrack ->
                    setupMainVideoIfNeeded(videoTrack as? VideoTrack, viewBinding)
                }
            }
            launch {
                cameraVideoTrackFlow.collectLatest { videoTrack ->
                    setupCameraVideoIfNeeded(videoTrack as? VideoTrack, viewBinding)
                }
            }

            // For local participants, mirror camera if using front camera.
            if (participant == room.localParticipant) {
                launch {
                    mainVideoTrackFlow
                        .flatMapLatestOrNull { track -> (track as LocalVideoTrack)::options.flow }
                        .collectLatest { options ->
                            viewBinding.renderer.setMirror(options?.position == CameraPosition.FRONT)
                        }
                }
            }
        }

        // Handle muted changes
        coroutineScope?.launch {
            videoTrackPubFlow
                .map { publications -> publications.screenShare ?: publications.camera }
                .flatMapLatestOrNull { publication ->
                    publication::muted.flow.map { muted -> publication to muted }
                }
                .collectLatest { publicationAndMuted ->
                    val (publication, muted) = publicationAndMuted ?: return@collectLatest
                    val keepCameraRendererVisible = publication.source == Track.Source.CAMERA
                    viewBinding.renderer.visibleOrInvisible(keepCameraRendererVisible || !muted)
                }
        }
        coroutineScope?.launch {
            videoTrackPubFlow
                .map { publications ->
                    publications.screenShare != null && publications.camera?.track != null
                }
                .collectLatest { hasCameraInset ->
                    viewBinding.cameraRenderer.visibleOrGone(hasCameraInset)
                }
        }
        val existingTrack = getVideoTrack()
        if (existingTrack != null) {
            setupMainVideoIfNeeded(existingTrack, viewBinding)
        }
    }

    private fun getVideoTrack(): VideoTrack? {
        return participant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
    }

    private fun setupMainVideoIfNeeded(videoTrack: VideoTrack?, viewBinding: ParticipantItemBinding) {
        if (boundMainVideoTrack == videoTrack) {
            return
        }
        boundMainVideoTrack?.removeRenderer(viewBinding.renderer)
        boundMainVideoTrack = videoTrack
        LKLog.v { "adding main renderer to $videoTrack" }
        videoTrack?.addRenderer(viewBinding.renderer)
    }

    private fun setupCameraVideoIfNeeded(videoTrack: VideoTrack?, viewBinding: ParticipantItemBinding) {
        if (boundCameraVideoTrack == videoTrack) {
            return
        }
        boundCameraVideoTrack?.removeRenderer(viewBinding.cameraRenderer)
        boundCameraVideoTrack = videoTrack
        LKLog.v { "adding camera inset renderer to $videoTrack" }
        videoTrack?.addRenderer(viewBinding.cameraRenderer)
    }

    override fun unbind(viewHolder: GroupieViewHolder<ParticipantItemBinding>) {
        coroutineScope?.cancel()
        coroutineScope = null
        teardownRenderers(viewHolder.binding)
        super.unbind(viewHolder)
    }

    override fun getLayout(): Int =
        if (speakerView) {
            R.layout.speaker_view
        } else {
            R.layout.participant_item
        }
}

private data class VideoPublications(
    val screenShare: TrackPublication?,
    val camera: TrackPublication?,
)

private fun View.visibleOrGone(visible: Boolean) {
    visibility = if (visible) {
        View.VISIBLE
    } else {
        View.GONE
    }
}

private fun View.visibleOrInvisible(visible: Boolean) {
    visibility = if (visible) {
        View.VISIBLE
    } else {
        View.INVISIBLE
    }
}

private fun showFocus(binding: ParticipantItemBinding) {
    binding.speakingIndicator.visibility = View.VISIBLE
}

private fun hideFocus(binding: ParticipantItemBinding) {
    binding.speakingIndicator.visibility = View.INVISIBLE
}

private inline fun <T, R> Flow<T?>.flatMapLatestOrNull(
    crossinline transform: suspend (value: T) -> Flow<R>,
): Flow<R?> {
    return flatMapLatest {
        if (it == null) {
            flowOf(null)
        } else {
            transform(it)
        }
    }
}
