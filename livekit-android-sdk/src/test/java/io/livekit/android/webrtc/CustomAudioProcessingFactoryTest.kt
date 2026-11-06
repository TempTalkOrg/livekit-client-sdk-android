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

package io.livekit.android.webrtc

import io.livekit.android.audio.AudioProcessorInterface
import io.livekit.android.audio.AudioProcessorOptions
import livekit.org.webrtc.ExternalAudioProcessingFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.nio.ByteBuffer

class CustomAudioProcessingFactoryTest {

    private lateinit var external: ExternalAudioProcessingFactory
    private lateinit var factory: CustomAudioProcessingFactory
    private lateinit var processor: AudioProcessorInterface
    private lateinit var captureBridge: ExternalAudioProcessingFactory.AudioProcessing

    @Before
    fun setUp() {
        external = mock()
        processor = mock()
        factory = CustomAudioProcessingFactory(
            AudioProcessorOptions(capturePostProcessor = processor),
            external,
        )
        val captor = argumentCaptor<ExternalAudioProcessingFactory.AudioProcessing>()
        verify(external, atLeastOnce()).setCapturePostProcessing(captor.capture())
        captureBridge = captor.lastValue
        clearInvocations(external)
        clearInvocations(processor)
    }

    @Test
    fun closeClearsCapturePostProcessor() {
        assertSame(processor, factory.capturePostProcessor)

        factory.close()

        assertNull(factory.capturePostProcessor)
    }

    @Test
    fun closeDoesNotCallNativeCaptureSetter() {
        factory.close()

        verify(external, never()).setCapturePostProcessing(any())
        verify(external, never()).setRenderPreProcessing(any())
        verify(external, never()).destroy()
    }

    @Test
    fun closeDetachesProcessorFromStableBridge() {
        val buffer = ByteBuffer.allocateDirect(4)

        captureBridge.process(1, 1, buffer)
        verify(processor).processAudio(1, 1, buffer)
        clearInvocations(processor)

        factory.close()
        captureBridge.process(1, 1, buffer)

        verify(processor, never()).processAudio(any(), any(), any())
    }

    @Test
    fun closeIsIdempotent() {
        factory.close()
        clearInvocations(external)

        factory.close()

        verify(external, never()).setCapturePostProcessing(any())
        verify(external, never()).destroy()
        assertNull(factory.capturePostProcessor)
    }

    @Test
    fun assignmentAfterCloseIsDiscarded() {
        factory.close()
        clearInvocations(external)

        val replacement = mock<AudioProcessorInterface>()
        factory.capturePostProcessor = replacement

        assertNull(factory.capturePostProcessor)
        verify(external, never()).setCapturePostProcessing(any())
        assertFalse(captureBridgeHolds(replacement))
    }

    @Test
    fun crossRoomCloseDoesNotOverwriteNewerNativeProcessor() {
        val newerExternal = mock<ExternalAudioProcessingFactory>()
        val newerProcessor = mock<AudioProcessorInterface>()
        val newerFactory = CustomAudioProcessingFactory(
            AudioProcessorOptions(capturePostProcessor = newerProcessor),
            newerExternal,
        )
        clearInvocations(newerExternal)

        // Old room teardown must not rebind the process-global native processor.
        factory.close()

        verify(external, never()).setCapturePostProcessing(any())
        verify(newerExternal, never()).setCapturePostProcessing(any())
        assertSame(newerProcessor, newerFactory.capturePostProcessor)
        assertTrue(captureBridgeHolds(null))
    }

    private fun captureBridgeHolds(expected: AudioProcessorInterface?): Boolean {
        val buffer = ByteBuffer.allocateDirect(4)
        clearInvocations(processor)
        if (expected != null) {
            clearInvocations(expected)
        }
        captureBridge.process(1, 1, buffer)
        return try {
            if (expected == null) {
                verify(processor, never()).processAudio(any(), any(), any())
                true
            } else {
                verify(expected).processAudio(1, 1, buffer)
                true
            }
        } catch (_: AssertionError) {
            false
        }
    }
}
