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

package io.livekit.android.webrtc

import io.livekit.android.audio.AudioProcessorInterface
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.audio.AuthedAudioProcessingController
import io.livekit.android.audio.AuthedAudioProcessorInterface
import io.livekit.android.util.LKLog
import io.livekit.android.util.MutableStateFlowDelegate
import io.livekit.android.util.flowDelegate
import livekit.org.webrtc.AudioProcessingFactory
import livekit.org.webrtc.ExternalAudioProcessingFactory
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @suppress
 *
 * Owns the Java-side audio processor bridges for a single LiveKit component /
 * Room lifetime. Capture and render each keep one stable [AudioProcessingBridge]
 * registered with WebRTC. Teardown clears those bridges and observable state
 * without calling [ExternalAudioProcessingFactory.destroy] or rebinding the
 * process-global native current processor, which would race a newer Room.
 */
internal class CustomAudioProcessingFactory(
    private val externalAudioProcessor: ExternalAudioProcessingFactory = ExternalAudioProcessingFactory(),
) : AuthedAudioProcessingController, Closeable {
    constructor(audioProcessorOptions: AudioProcessorOptions) : this() {
        applyOptions(audioProcessorOptions)
    }

    constructor(
        audioProcessorOptions: AudioProcessorOptions,
        externalAudioProcessor: ExternalAudioProcessingFactory,
    ) : this(externalAudioProcessor) {
        applyOptions(audioProcessorOptions)
    }

    private val closed = AtomicBoolean(false)
    private val factoryId = System.identityHashCode(this)
    private val captureBridge = AudioProcessingBridge("capture", factoryId, closed)
    private val renderBridge = AudioProcessingBridge("render", factoryId, closed)

    private val capturePostProcessorDelegate: MutableStateFlowDelegate<AudioProcessorInterface?> =
        flowDelegate(null, ::onCapturePostProcessorSet)

    private val renderPreProcessorDelegate: MutableStateFlowDelegate<AudioProcessorInterface?> =
        flowDelegate(null, ::onRenderPreProcessorSet)

    init {
        // Register stable bridges once. Later processor swaps only mutate the
        // bridge fields so release/teardown never needs to touch native setters.
        externalAudioProcessor.setCapturePostProcessing(captureBridge)
        externalAudioProcessor.setRenderPreProcessing(renderBridge)
        LKLog.i {
            "[AudioProcessorLifecycle] factory created " +
                "factory=$factoryId external=${System.identityHashCode(externalAudioProcessor)} " +
                "captureBridge=${System.identityHashCode(captureBridge)} " +
                "renderBridge=${System.identityHashCode(renderBridge)}"
        }
    }

    private fun applyOptions(audioProcessorOptions: AudioProcessorOptions) {
        capturePostProcessor = audioProcessorOptions.capturePostProcessor
        renderPreProcessor = audioProcessorOptions.renderPreProcessor
        bypassCapturePostProcessing = audioProcessorOptions.capturePostBypass
        bypassRenderPreProcessing = audioProcessorOptions.renderPreBypass
    }

    private fun onCapturePostProcessorSet(
        value: AudioProcessorInterface?,
        @Suppress("UNUSED_PARAMETER") oldValue: AudioProcessorInterface?,
    ) {
        if (closed.get()) {
            captureBridge.audioProcessing = null
            capturePostProcessorDelegate.setValueSilently(null)
            return
        }
        captureBridge.audioProcessing = value
    }

    private fun onRenderPreProcessorSet(
        value: AudioProcessorInterface?,
        @Suppress("UNUSED_PARAMETER") oldValue: AudioProcessorInterface?,
    ) {
        if (closed.get()) {
            renderBridge.audioProcessing = null
            renderPreProcessorDelegate.setValueSilently(null)
            return
        }
        renderBridge.audioProcessing = value
    }

    override var capturePostProcessor: AudioProcessorInterface? by capturePostProcessorDelegate

    override var renderPreProcessor: AudioProcessorInterface? by renderPreProcessorDelegate

    override var bypassCapturePostProcessing: Boolean by flowDelegate(false) { value, _ ->
        if (!closed.get()) {
            externalAudioProcessor.setBypassFlagForCapturePost(value)
        }
    }

    override var bypassRenderPreProcessing: Boolean by flowDelegate(false) { value, _ ->
        if (!closed.get()) {
            externalAudioProcessor.setBypassFlagForRenderPre(value)
        }
    }

    fun getAudioProcessingFactory(): AudioProcessingFactory {
        return externalAudioProcessor
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            LKLog.i {
                "[AudioProcessorLifecycle] factory close skipped factory=$factoryId reason=already_closed"
            }
            return
        }
        LKLog.i {
            "[AudioProcessorLifecycle] factory close begin " +
                "factory=$factoryId captureProcessor=${captureBridge.processorId()} " +
                "renderProcessor=${renderBridge.processorId()}"
        }
        captureBridge.audioProcessing = null
        renderBridge.audioProcessing = null
        // Clear observable state without re-entering native setters.
        capturePostProcessorDelegate.setValueSilently(null)
        renderPreProcessorDelegate.setValueSilently(null)
        LKLog.i {
            "[AudioProcessorLifecycle] factory close complete " +
                "factory=$factoryId captureBridgeCleared=true renderBridgeCleared=true"
        }
    }

    override fun authenticate(url: String, token: String) {
        (capturePostProcessor as? AuthedAudioProcessorInterface)?.authenticate(url, token)
        (renderPreProcessor as? AuthedAudioProcessorInterface)?.authenticate(url, token)
    }

    @Deprecated("Use the capturePostProcessing variable directly instead", ReplaceWith("capturePostProcessor = processing"))
    override fun setCapturePostProcessing(processing: AudioProcessorInterface?) {
        capturePostProcessor = processing
    }

    @Deprecated("Use the renderPreProcessing variable directly instead", ReplaceWith("renderPreProcessor = processing"))
    override fun setRenderPreProcessing(processing: AudioProcessorInterface?) {
        renderPreProcessor = processing
    }

    @Deprecated("Use the bypassCapturePostProcessing variable directly instead", ReplaceWith("bypassCapturePostProcessing = bypass"))
    override fun setBypassForCapturePostProcessing(bypass: Boolean) {
        bypassCapturePostProcessing = bypass
    }

    @Deprecated("Use the bypassRendererPreProcessing variable directly instead", ReplaceWith("bypassRenderPreProcessing = bypass"))
    override fun setBypassForRenderPreProcessing(bypass: Boolean) {
        bypassRenderPreProcessing = bypass
    }

    private class AudioProcessingBridge(
        private val direction: String,
        private val factoryId: Int,
        private val closed: AtomicBoolean,
        @Volatile var audioProcessing: AudioProcessorInterface? = null,
    ) : ExternalAudioProcessingFactory.AudioProcessing {
        private val postCloseWarningLogged = AtomicBoolean(false)

        fun processorId(): String =
            audioProcessing?.let { System.identityHashCode(it).toString() } ?: "null"

        private fun currentProcessor(callback: String): AudioProcessorInterface? {
            if (closed.get()) {
                if (postCloseWarningLogged.compareAndSet(false, true)) {
                    LKLog.w {
                        "[AudioProcessorLifecycle] bridge callback after close " +
                            "factory=$factoryId direction=$direction callback=$callback " +
                            "bridge=${System.identityHashCode(this)}"
                    }
                }
                return null
            }
            return audioProcessing
        }

        override fun initialize(sampleRateHz: Int, numChannels: Int) {
            currentProcessor("initialize")?.initializeAudioProcessing(sampleRateHz, numChannels)
        }

        override fun reset(newRate: Int) {
            currentProcessor("reset")?.resetAudioProcessing(newRate)
        }

        override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer?) {
            currentProcessor("process")?.processAudio(numBands, numFrames, buffer!!)
        }
    }
}
