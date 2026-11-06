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

package io.livekit.android.room.track

import io.livekit.android.audio.AudioBufferCallbackDispatcher
import io.livekit.android.audio.AudioProcessingController
import io.livekit.android.audio.AudioProcessorInterface
import io.livekit.android.audio.AudioRecordSamplesDispatcher
import io.livekit.android.audio.NoAudioRecordPrewarmer
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.flowDelegate
import io.livekit.android.webrtc.peerconnection.RTCThreadToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import livekit.org.webrtc.AudioTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LocalAudioTrackDisposeTest {

    @Test
    fun disposeCancelsDelegateScope() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val audioProcessingController = TestAudioProcessingController()
        val track = LocalAudioTrack(
            name = "mic",
            mediaTrack = TestAudioStreamTrack(),
            options = LocalAudioTrackOptions(),
            audioProcessingController = audioProcessingController,
            dispatcher = dispatcher,
            audioRecordSamplesDispatcher = AudioRecordSamplesDispatcher(),
            audioBufferCallbackDispatcher = AudioBufferCallbackDispatcher(),
            audioRecordPrewarmer = NoAudioRecordPrewarmer(),
            rtcThreadToken = AlwaysValidRtcThreadToken,
        )

        // Touch features so Eagerly stateIn collector is definitely started.
        assertTrue(track.features.isNotEmpty() || track.features.isEmpty())
        advanceUntilIdle()

        val scopeField = LocalAudioTrack::class.java.getDeclaredField("delegateScope")
        scopeField.isAccessible = true
        val delegateScope = scopeField.get(track) as CoroutineScope
        assertTrue(delegateScope.isActive)

        track.dispose()
        advanceUntilIdle()

        assertFalse(delegateScope.isActive)
    }

    private class TestAudioProcessingController : AudioProcessingController {
        @FlowObservable
        @get:FlowObservable
        override var capturePostProcessor: AudioProcessorInterface? by flowDelegate(null)

        @FlowObservable
        @get:FlowObservable
        override var renderPreProcessor: AudioProcessorInterface? by flowDelegate(null)

        @FlowObservable
        @get:FlowObservable
        override var bypassRenderPreProcessing: Boolean by flowDelegate(false)

        @FlowObservable
        @get:FlowObservable
        override var bypassCapturePostProcessing: Boolean by flowDelegate(false)

        @Deprecated("Use capturePostProcessor")
        override fun setCapturePostProcessing(processing: AudioProcessorInterface?) {
            capturePostProcessor = processing
        }

        @Deprecated("Use bypassCapturePostProcessing")
        override fun setBypassForCapturePostProcessing(bypass: Boolean) {
            bypassCapturePostProcessing = bypass
        }

        @Deprecated("Use renderPreProcessor")
        override fun setRenderPreProcessing(processing: AudioProcessorInterface?) {
            renderPreProcessor = processing
        }

        @Deprecated("Use bypassRenderPreProcessing")
        override fun setBypassForRenderPreProcessing(bypass: Boolean) {
            bypassRenderPreProcessing = bypass
        }
    }

    private class TestAudioStreamTrack : AudioTrack(1L) {
        private var disposed = false

        override fun id(): String = "test-audio"

        override fun kind(): String = AUDIO_TRACK_KIND

        override fun enabled(): Boolean = true

        override fun setEnabled(enable: Boolean): Boolean = true

        override fun state(): State = if (disposed) State.ENDED else State.LIVE

        override fun dispose() {
            disposed = true
        }

        override fun setVolume(volume: Double) {
        }
    }

    private object AlwaysValidRtcThreadToken : RTCThreadToken {
        override val isDisposed: Boolean
            get() = false
    }
}
