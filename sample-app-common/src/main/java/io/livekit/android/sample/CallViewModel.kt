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

package io.livekit.android.sample

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.MediaSendConnectionState
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.datastream.incoming.TextStreamReceiver
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalScreencastVideoTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoPreset169
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.rpc.RpcError
import io.livekit.android.sample.common.BuildConfig
import io.livekit.android.sample.model.StressTest
import io.livekit.android.sample.proxy.ProxyConfig
import io.livekit.android.sample.service.ForegroundService
import io.livekit.android.util.LKLog
import io.livekit.android.util.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import livekit.LivekitTemptalk
import livekit.org.webrtc.CameraXHelper

@kotlinx.serialization.Serializable
data class CipherMessageParam(
    val content: String,
    val registrationId: Int,
    val uid: String,
)

@kotlinx.serialization.Serializable
data class EncInfoParam(
    val emk: String,
    val uid: String,
)

@kotlinx.serialization.Serializable
data class NotificationArgsParam(
    val collapseId: String,
)

@kotlinx.serialization.Serializable
data class NotificationParam(
    val type: Int,
    val args: NotificationArgsParam? = null,
)

@kotlinx.serialization.Serializable
data class StartCallParam(
    val type: String,
    val version: Int,
    val timestamp: Long,
    val conversationId: String,
    val publicKey: String,
    val cipherMessages: List<CipherMessageParam>,
    val encInfos: List<EncInfoParam>,
    val notification: NotificationParam,
)

@kotlinx.serialization.Serializable
data class MergeStartCallParam(
    val startCall: StartCallParam,
    val token: String,
    val userAgent: String,
)

enum class MediaSendUiState {
    NONE,
    ROOM_RECOVERING,
    MEDIA_RECOVERING,
}

fun resolveMediaSendUiState(
    roomState: Room.State,
    mediaSendState: MediaSendConnectionState,
): MediaSendUiState = when {
    roomState == Room.State.DISCONNECTED -> MediaSendUiState.NONE
    roomState == Room.State.RECONNECTING ||
        mediaSendState == MediaSendConnectionState.ROOM_RECOVERING -> MediaSendUiState.ROOM_RECOVERING
    roomState != Room.State.CONNECTED -> MediaSendUiState.NONE
    mediaSendState == MediaSendConnectionState.RECOVERING ||
        mediaSendState == MediaSendConnectionState.FAILED -> MediaSendUiState.MEDIA_RECOVERING
    else -> MediaSendUiState.NONE
}

@OptIn(ExperimentalCamera2Interop::class)
class CallViewModel(
    val url: String,
    val token: String,
    application: Application,
    val e2ee: Boolean = false,
    val e2eeKey: String? = "",
    val quic: Boolean = false,
    val quicDeviceType: Int = 0,
    val quicCidTag: String = "",
    val serverHost: String = "",
    val caCertPem: String = "",
    val proxyConfig: ProxyConfig? = null,
    val audioProcessorOptions: AudioProcessorOptions? = null,
    val stressTest: StressTest = StressTest.None,
    val quicConnectTimeoutMs: Int = DEFAULT_QUIC_CONNECT_TIMEOUT_MS,
) : AndroidViewModel(application) {

    private fun getE2EEOptions(): E2EEOptions? {
        var e2eeOptions = if (e2ee && e2eeKey != null) {
            E2EEOptions()
        } else {
            null
        }
        if (!BuildConfig.USE_MERGE_START_CALL) {
            e2eeOptions?.keyProvider?.setSharedKey(e2eeKey!!)
        }
        return e2eeOptions
    }

    private fun getConnectOptions(): ConnectOptions {
        // RTC proxy: when configured, force WebRTC media through the operator's TURN
        // relay (relay-only ICE) with an SPKI-pinned outer TLS. Both derive from the
        // same config so they cannot drift. Null when the proxy is disabled.
        val proxyRtcConfig = proxyConfig?.buildRtcConfig()
        val proxyTlsVerifier = proxyConfig?.createTurnTlsVerifier()

        // QUIC-over-proxy (MASQUE CONNECT-UDP): when a proxy is selected and QUIC
        // signaling is on, tunnel the QUIC signaling through the same proxy host,
        // SPKI-pinning the outer hop. Ignored by the SDK unless useQuicSignal=true.
        val quicProxyHost = proxyConfig?.host
        val quicProxyPort = proxyConfig?.port ?: 0
        val quicProxySni = proxyConfig?.outerSni()
        val quicProxySpkiPin = proxyConfig?.spkiPinBase64

        if (!BuildConfig.USE_MERGE_START_CALL || BuildConfig.MERGE_START_CALL_PARAM.isNullOrBlank()) {
            return ConnectOptions(
                useQuicSignal = quic,
                quicDeviceType = quicDeviceType,
                quicCidTag = quicCidTag,
                serverHost = serverHost.ifEmpty { null },
                caCertPem = caCertPem.ifEmpty { null },
                rtcConfig = proxyRtcConfig,
                sslCertificateVerifier = proxyTlsVerifier,
                quicProxyHost = quicProxyHost,
                quicProxyPort = quicProxyPort,
                quicProxySni = quicProxySni,
                quicProxySpkiPin = quicProxySpkiPin,
            ).withQuicConnectTimeout(quicConnectTimeoutMs)
        }
        val param = Json.decodeFromString<MergeStartCallParam>(BuildConfig.MERGE_START_CALL_PARAM)

        val cipherMessages = param.startCall.cipherMessages.map {
            LivekitTemptalk.TTCipherMessages.newBuilder().apply {
                content = it.content
                registrationId = it.registrationId
                uid = it.uid
            }.build()
        }
        val encInfos = param.startCall.encInfos.map {
            LivekitTemptalk.TTEncInfo.newBuilder().apply {
                emk = it.emk
                uid = it.uid
            }.build()
        }
        val notification = LivekitTemptalk.TTNotification.newBuilder().apply {
            type = param.startCall.notification.type
            args = param.startCall.notification.args?.let { a ->
                LivekitTemptalk.TTNotification.TTArgs.newBuilder().apply {
                    collapseId = a.collapseId
                }.build()
            }
        }.build()
        val startCall = LivekitTemptalk.TTStartCall.newBuilder().apply {
            type = param.startCall.type
            version = param.startCall.version
            timestamp = param.startCall.timestamp
            conversationId = param.startCall.conversationId
            publicKey = param.startCall.publicKey
            clientCallId = room.localId
            addAllCipherMessages(cipherMessages)
            addAllEncInfos(encInfos)
            this.notification = notification
        }.build()
        val ttCallRequest = LivekitTemptalk.TTCallRequest.newBuilder().apply {
            token = param.token
            userAgent = param.userAgent
            this.startCall = startCall
        }.build()

        return ConnectOptions(
            ttCallRequest = ttCallRequest,
            userAgent = param.userAgent,
            useQuicSignal = quic,
            quicDeviceType = quicDeviceType,
            quicCidTag = quicCidTag,
            serverHost = serverHost.ifEmpty { null },
            caCertPem = caCertPem.ifEmpty { null },
            rtcConfig = proxyRtcConfig,
            sslCertificateVerifier = proxyTlsVerifier,
            quicProxyHost = quicProxyHost,
            quicProxyPort = quicProxyPort,
            quicProxySni = quicProxySni,
            quicProxySpkiPin = quicProxySpkiPin,
        ).withQuicConnectTimeout(quicConnectTimeoutMs)
    }

    private fun getRoomOptions(): RoomOptions {
        return RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            e2eeOptions = getE2EEOptions(),
            audioTrackCaptureDefaults = LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = true,
                autoGainControl = true,
                highPassFilter = true,
                typingNoiseDetection = true,
            ),
            videoTrackCaptureDefaults = LocalVideoTrackOptions(
                deviceId = "",
                position = CameraPosition.FRONT,
                captureParams = VideoCaptureParameter(1280, 720, 30),
                isPortrait = true // Set portrait mode for vertical video capture orientation
            ),
            videoTrackPublishDefaults = VideoTrackPublishDefaults(
                videoEncoding = VideoPreset169.H1080.encoding,
                videoCodec = VideoCodec.VP9.codecName,
                scalabilityMode = "L3T3"
            ),
        )
    }

    val room = LiveKit.create(
        appContext = application,
        options = getRoomOptions(),
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(
                audioHandler = AudioSwitchHandler(context = application).apply {
                    loggingEnabled = BuildConfig.DEBUG
                    preferredDeviceList = listOf(
                        AudioDevice.BluetoothHeadset::class.java,
                        AudioDevice.WiredHeadset::class.java,
                        AudioDevice.Speakerphone::class.java,
                        AudioDevice.Earpiece::class.java
                    )
                },
                audioProcessorOptions = audioProcessorOptions,
            ),
        ),
    ).apply { enableMetrics = true }

    private var curretnRotation: Int? = null

    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null
    val audioHandler = room.audioHandler as AudioSwitchHandler

    // 1v1 入会计时探针：记录本端 connected / 对端加入的时刻，用于观测各阶段时间差
    private var callTimingConnectedAtMs = 0L
    private var callTimingRemoteJoinedAtMs = 0L

    /** 三端统一日志格式，方便直接比对各阶段时间差。 */
    private fun logCallTiming(stage: String, extra: String = "") {
        val now = SystemClock.elapsedRealtime()
        val sinceConnected = if (callTimingConnectedAtMs > 0) now - callTimingConnectedAtMs else -1
        val sinceRemoteJoined = if (callTimingRemoteJoinedAtMs > 0) now - callTimingRemoteJoinedAtMs else -1
        LKLog.i { "[1v1Timing] stage=$stage sinceConnected=${sinceConnected}ms sinceRemoteJoined=${sinceRemoteJoined}ms$extra" }
    }

    // 媒体就绪探针：本端麦克风轨道被订阅即视为就绪。
    // 若订阅信号缺失，则保留超时兜底，避免永远等不到就绪。
    private var micTrackSubscribed = false
    private var mediaReadyReported = false
    private var mediaReadyFallbackJob: Job? = null

    /** 对端进房时装载，避免订阅信号缺失时永远等不到就绪。 */
    private fun armMediaReadyGate() {
        if (mediaReadyReported || mediaReadyFallbackJob != null) return
        mediaReadyFallbackJob = viewModelScope.launch {
            delay(MEDIA_READY_FALLBACK_MS)
            reportMediaReady("fallbackTimeout")
        }
    }

    private fun onMicTrackSubscribed() {
        if (micTrackSubscribed) return
        micTrackSubscribed = true
        reportMediaReady("trackSubscribed")
    }

    /** 幂等：一次会话只报一次，重连不重报。 */
    private fun reportMediaReady(reason: String) {
        if (mediaReadyReported) return
        mediaReadyReported = true
        mediaReadyFallbackJob?.cancel()
        mediaReadyFallbackJob = null
        logCallTiming(
            "mediaReady",
            " reason=$reason micTrackSubscribed=$micTrackSubscribed",
        )
    }

    private fun resetMediaReadyGate() {
        mediaReadyFallbackJob?.cancel()
        mediaReadyFallbackJob = null
        micTrackSubscribed = false
        mediaReadyReported = false
    }

    val participants = room::remoteParticipants.flow
        .map { remoteParticipants ->
            listOf<Participant>(room.localParticipant) +
                remoteParticipants
                    .keys
                    .sortedBy { it.value }
                    .mapNotNull { remoteParticipants[it] }
        }

    private val mutableError = MutableStateFlow<Throwable?>(null)
    val error = mutableError.hide()

    private val mutableConnectionStatus = MutableStateFlow("Connecting")
    val connectionStatus = mutableConnectionStatus.hide()

    private val mutableMediaSendUiState = MutableStateFlow(MediaSendUiState.NONE)
    val mediaSendUiState = mutableMediaSendUiState.hide()

    private val mutablePrimarySpeaker = MutableStateFlow<Participant?>(null)
    val primarySpeaker: StateFlow<Participant?> = mutablePrimarySpeaker

    val activeSpeakers = room::activeSpeakers.flow
    val ttCallResp = room::ttCallResp.flow

    private var localScreencastTrack: LocalScreencastVideoTrack? = null

    // Controls
    val micEnabled = room.localParticipant::isMicrophoneEnabled.flow
    val cameraEnabled = room.localParticipant::isCameraEnabled.flow
    val screenshareEnabled = room.localParticipant::isScreenShareEnabled.flow

    private val mutableEnhancedNsEnabled = MutableLiveData(false)
    val enhancedNsEnabled = mutableEnhancedNsEnabled.hide()

    private val mutableEnableAudioProcessor = MutableLiveData(true)
    val enableAudioProcessor = mutableEnableAudioProcessor.hide()

    // Emits a string whenever a data message is received.
    private val mutableDataReceived = MutableSharedFlow<String>()
    val dataReceived = mutableDataReceived

    // Whether other participants are allowed to subscribe to this participant's tracks.
    private val mutablePermissionAllowed = MutableStateFlow(true)
    val permissionAllowed = mutablePermissionAllowed.hide()

    // RPC tester state. Lives on the ViewModel so it survives dialog dismiss/reopen.
    private val mutableHandlers = MutableStateFlow<List<RpcHandlerState>>(emptyList())
    val handlers: StateFlow<List<RpcHandlerState>> = mutableHandlers

    init {

        CameraXHelper.createCameraProvider(ProcessLifecycleOwner.get()).let {
            if (it.isSupported(application)) {
                CameraCapturerUtils.registerCameraProvider(it)
                cameraProvider = it
            }
        }

        viewModelScope.launch {
            combine(
                room::state.flow,
                room::mediaSendConnectionState.flow,
            ) { roomState, mediaSendState ->
                roomState to mediaSendState
            }.collect { (roomState, mediaSendState) ->
                mutableMediaSendUiState.value = resolveMediaSendUiState(roomState, mediaSendState)
            }
        }

        // Handling text streams
        room.registerTextStreamHandler(
            topic = "lk.chat",
            handler = { receiver: TextStreamReceiver, identity: Participant.Identity ->
                viewModelScope.launch {
                    val message = receiver.readAll().joinToString(separator = "")
                    mutableDataReceived.emit("$identity: $message")
                }
            },
        )

        viewModelScope.launch(Dispatchers.Default) {
            // Collect any errors.
            launch {
                error.collect { LKLog.e(it) }
            }

            // Handle any changes in speakers.
            launch {
                combine(participants, activeSpeakers) { participants, speakers -> participants to speakers }
                    .collect { (participantsList, speakers) ->
                        handlePrimarySpeaker(
                            participantsList,
                            speakers,
                            room,
                        )
                    }
            }

            if (BuildConfig.USE_MERGE_START_CALL) {
                launch {
                    ttCallResp.collect { response ->
                        LKLog.i { "[startcall]: response=$response" }
                    }
                }
            }

            // Handle room events.
            launch {
                room.events.collect {
                    when (it) {
                        is RoomEvent.FailedToConnect -> {
                            mutableError.value = it.error
                            mutableConnectionStatus.value = "Failed to connect"
                        }
                        is RoomEvent.Connected -> {
                            mutableConnectionStatus.value = "Connected"

                            callTimingConnectedAtMs = SystemClock.elapsedRealtime()
                            callTimingRemoteJoinedAtMs = 0L
                            resetMediaReadyGate()
                            logCallTiming("roomConnected", " remoteCount=${room.remoteParticipants.size}")

                            // 后加入方在 connected 时对端就已在房间里，不会再收到 ParticipantConnected
                            room.remoteParticipants.values.firstOrNull()?.let { remote ->
                                callTimingRemoteJoinedAtMs = callTimingConnectedAtMs
                                logCallTiming(
                                    "remoteAlreadyPresent",
                                    " identity=${remote.identity} alreadyActive=${remote.state == Participant.State.ACTIVE}",
                                )
                                armMediaReadyGate()
                            }
                        }
                        is RoomEvent.MediaSendConnectionStateChanged -> {
                            LKLog.i { "mediaSendConnectionState ${it.oldState} -> ${it.state}" }
                        }
                        is RoomEvent.DataReceived -> {
                            // Handling basic data packets.
                            val identity = it.participant?.identity ?: "server"
                            val message = it.data.toString(Charsets.UTF_8)
                            LKLog.i { "DataReceived $identity: $message" }
                            // mutableDataReceived.emit("$identity: $message")
                        }

                        is RoomEvent.Disconnected -> {
                            LKLog.e(it.error) { "Disconnected reason:${it.reason}" }
                            mutableConnectionStatus.value = "Disconnected (${it.reason})"
                            resetMediaReadyGate()
                        }

                        is RoomEvent.Reconnecting -> {
                            LKLog.i { "Reconnecting" }
                            mutableConnectionStatus.value = "Reconnecting"
                        }

                        is RoomEvent.Reconnected -> {
                            LKLog.i { "Reconnected" }
                            mutableConnectionStatus.value = "Reconnected"
                        }

                        is RoomEvent.ConnectionQualityChanged -> {
                            logConnectionQuality(it.participant, it.quality)
                        }

                        is RoomEvent.ParticipantDisconnected -> {
                            LKLog.i { "ParticipantDisconnected: ${it.participant.identity} ${it.participant.sid}" }
                        }

                        is RoomEvent.ParticipantConnected -> {
                            LKLog.i { "ParticipantConnected: ${it.participant.identity} ${it.participant.sid}" }

                            if (callTimingRemoteJoinedAtMs == 0L) {
                                callTimingRemoteJoinedAtMs = SystemClock.elapsedRealtime()
                            }
                            logCallTiming("remoteJoined", " identity=${it.participant.identity}")
                            armMediaReadyGate()
                        }

                        is RoomEvent.ParticipantStateChanged -> {
                            if (it.participant is RemoteParticipant) {
                                val stage = if (it.newState == Participant.State.ACTIVE) {
                                    "participantActive"
                                } else {
                                    "participantState"
                                }
                                logCallTiming(stage, " identity=${it.participant.identity} state=${it.newState}")
                            }
                        }

                        is RoomEvent.LocalTrackSubscribed -> {
                            logCallTiming(
                                "localTrackSubscribed",
                                " source=${it.publication.source} sid=${it.publication.sid}",
                            )
                            // 只有麦克风轨道被订阅才算；摄像头/屏共被订阅与「能否听见我」无关。
                            if (it.publication.source == Track.Source.MICROPHONE) onMicTrackSubscribed()
                        }

                        is RoomEvent.TrackMuted -> {
                            LKLog.i {
                                "TrackMuted: [${it.publication.source}: ${it.publication.sid}, " +
                                    "${it.publication.track?.sid}] [${it.participant.sid},${it.participant.identity}]"
                            }
                        }

                        is RoomEvent.TrackUnmuted -> {
                            LKLog.i {
                                "TrackUnmuted: [${it.publication.source}: ${it.publication.sid}, " +
                                    "${it.publication.track?.sid}] [${it.participant.sid},${it.participant.identity}]"
                            }
                        }

                        is RoomEvent.TrackUnpublished -> {
                            LKLog.i {
                                "TrackUnpublished: [${it.publication.source}: ${it.publication.sid}, " +
                                    "${it.publication.track?.sid}] [${it.participant.sid},${it.participant.identity}] " +
                                    "[screen:${it.participant.funIsScreenShareEnabled()}]"
                            }
                        }

                        is RoomEvent.TrackPublished -> {
                            LKLog.i {
                                "TrackPublished: [${it.publication.source}: ${it.publication.sid}, " +
                                    "${it.publication.track?.sid}] [${it.participant.sid},${it.participant.identity}] " +
                                    "[screen:${it.participant.funIsScreenShareEnabled()}]"
                            }
                        }

                        else -> {
                            LKLog.i { "Room event: $it" }
                        }
                    }
                }
            }

            when (stressTest) {
                is StressTest.SwitchRoom -> launch { stressTest.execute() }
                is StressTest.None -> connectToRoom()
            }
        }

        // Start a foreground service to keep the call from being interrupted if the
        // app goes into the background.
        val foregroundServiceIntent = Intent(application, ForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(foregroundServiceIntent)
        } else {
            application.startService(foregroundServiceIntent)
        }
    }

    /** demo 不做归一化，原样记录 SDK 上报的档位即可。 */
    private fun logConnectionQuality(participant: Participant, quality: ConnectionQuality) {
        val identity = participant.identity?.value ?: participant.sid.value
        val isLocal = participant === room.localParticipant
        LKLog.i { "[nq] identity=$identity local=$isLocal quality=${quality.name.lowercase()}" }
    }

    private suspend fun collectTrackStats(event: RoomEvent.TrackSubscribed) {
        val pub = event.publication
        while (true) {
            delay(10000)
            if (pub.subscribed) {
                val statsReport = pub.track?.getRTCStats() ?: continue
                LKLog.e { "stats for ${pub.sid}:" }

                for (entry in statsReport.statsMap) {
                    LKLog.e { "${entry.key} = ${entry.value}" }
                }
            }
        }
    }

    fun toggleEnhancedNs(enabled: Boolean? = null) {
        if (enabled != null) {
            mutableEnableAudioProcessor.postValue(enabled)
            room.audioProcessingController.setBypassForCapturePostProcessing(!enabled)
            return
        }

        if (room.audioProcessorIsEnabled) {
            if (enableAudioProcessor.value == true) {
                room.audioProcessingController.setBypassForCapturePostProcessing(true)
                mutableEnableAudioProcessor.postValue(false)
            } else {
                room.audioProcessingController.setBypassForCapturePostProcessing(false)
                mutableEnableAudioProcessor.postValue(true)
            }
        }
    }

    private suspend fun connectToRoom() {
        try {
            mutableConnectionStatus.value = "Connecting"
            var tokenVer = if (BuildConfig.USE_MERGE_START_CALL) {
                ""
            } else {
                token
            }
            // room.e2eeOptions = getE2EEOptions()
            room.connect(
                url = url,
                token = tokenVer,
                options = getConnectOptions(),
            )

            mutableEnhancedNsEnabled.postValue(room.audioProcessorIsEnabled)
            mutableEnableAudioProcessor.postValue(true)

            // Create and publish audio/video tracks
            val localParticipant = room.localParticipant

            if (BuildConfig.OPEN_MICROPHONE) {
                localParticipant.setMicrophoneEnabled(true, publishMuted = BuildConfig.MICROPHONE_PUBLISH_MUTE)
            }

            if (BuildConfig.ENABLE_DEVICE_ROTATION) {
                localParticipant.deviceRotation = curretnRotation
            }

            if (BuildConfig.OPEN_CAMERA) {
                localParticipant.setCameraEnabled(true)
            }

            // Update the speaker
            handlePrimarySpeaker(emptyList(), emptyList(), room)
        } catch (e: Throwable) {
            LKLog.e { "Failed to connect to room, error=$e.message" }
            mutableError.value = e
            mutableConnectionStatus.value = "Failed to connect"
        }
    }

    private fun handlePrimarySpeaker(participantsList: List<Participant>, speakers: List<Participant>, room: Room?) {
        var speaker = mutablePrimarySpeaker.value

        // If speaker is local participant (due to defaults),
        // attempt to find another remote speaker to replace with.
        if (speaker is LocalParticipant) {
            val remoteSpeaker = participantsList
                .filterIsInstance<RemoteParticipant>() // Try not to display local participant as speaker.
                .firstOrNull()

            if (remoteSpeaker != null) {
                speaker = remoteSpeaker
            }
        }

        // If previous primary speaker leaves
        if (!participantsList.contains(speaker)) {
            // Default to another person in room, or local participant.
            speaker = participantsList.filterIsInstance<RemoteParticipant>()
                .firstOrNull()
                ?: room?.localParticipant
        }

        if (speakers.isNotEmpty() && !speakers.contains(speaker)) {
            val remoteSpeaker = speakers
                .filterIsInstance<RemoteParticipant>() // Try not to display local participant as speaker.
                .firstOrNull()

            if (remoteSpeaker != null) {
                speaker = remoteSpeaker
            }
        }

        mutablePrimarySpeaker.value = speaker
    }

    /**
     * Start a screen capture with the result intent from
     * [MediaProjectionManager.createScreenCaptureIntent]
     */
    fun startScreenCapture(mediaProjectionPermissionResultData: Intent) {
        val localParticipant = room.localParticipant
        viewModelScope.launch(Dispatchers.IO) {
            localParticipant.setScreenShareEnabled(true, ScreenCaptureParams(mediaProjectionPermissionResultData))
            val screencastTrack = localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? LocalScreencastVideoTrack
            this@CallViewModel.localScreencastTrack = screencastTrack
        }
    }

    fun stopScreenCapture() {
        viewModelScope.launch(Dispatchers.IO) {
            localScreencastTrack?.let { localScreencastVideoTrack ->
                localScreencastVideoTrack.stop()
                room.localParticipant.unpublishTrack(localScreencastVideoTrack)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()

        // Tear down any RPC handlers before releasing the room.
        mutableHandlers.value.forEach { handler ->
            runCatching { room.localParticipant.unregisterRpcMethod(handler.method) }
        }
        mutableHandlers.value = emptyList()

        // Make sure to release any resources associated with LiveKit
        room.disconnect()
        room.release()

        // Clean up foreground service
        val application = getApplication<Application>()
        val foregroundServiceIntent = Intent(application, ForegroundService::class.java)
        application.stopService(foregroundServiceIntent)
        cameraProvider?.let {
            CameraCapturerUtils.unregisterCameraProvider(it)
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            room.localParticipant.setMicrophoneEnabled(enabled)
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            room.localParticipant.setCameraEnabled(enabled)
        }
    }

    fun flipCamera() {
        if (!BuildConfig.ENABLE_DEVICE_ROTATION) {
            val videoTrack = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
                ?.track as? LocalVideoTrack
                ?: return

            val newPosition = when (videoTrack.options.position) {
                CameraPosition.FRONT -> CameraPosition.BACK
                CameraPosition.BACK -> CameraPosition.FRONT
                else -> null
            }

            videoTrack.switchCamera(position = newPosition)
        } else {
            var rotation: Int?
            if (curretnRotation == 90) {
                rotation = null
            } else {
                rotation = 90
            }

            curretnRotation = rotation

            room.localParticipant.deviceRotation = rotation
        }
    }

    fun dismissError() {
        mutableError.value = null
    }

    fun sendData(message: String) {
        viewModelScope.launch(Dispatchers.IO) {
            room.localParticipant.sendText(message, StreamTextOptions(topic = "lk.chat"))
        }
    }

    fun registerRpcHandler(method: String, initialResponse: String) {
        if (method.isBlank()) return
        val state = RpcHandlerState(
            method = method,
            staticResponse = MutableStateFlow(initialResponse),
            invocations = MutableStateFlow(emptyList()),
        )
        room.localParticipant.registerRpcMethod(method) { invocation ->
            val record = RpcInvocationRecord(
                timestamp = System.currentTimeMillis(),
                caller = invocation.callerIdentity,
                payload = invocation.payload,
            )
            state.invocations.value = state.invocations.value + record
            state.staticResponse.value
        }
        // Replace any prior entry for the same method (SDK overwrites anyway).
        mutableHandlers.value = mutableHandlers.value.filterNot { it.method == method } + state
    }

    fun unregisterRpcHandler(method: String) {
        room.localParticipant.unregisterRpcMethod(method)
        mutableHandlers.value = mutableHandlers.value.filterNot { it.method == method }
    }

    fun updateStaticResponse(method: String, response: String) {
        mutableHandlers.value.firstOrNull { it.method == method }
            ?.staticResponse?.let { it.value = response }
    }

    suspend fun performRpc(
        destination: Participant.Identity,
        method: String,
        payload: String,
    ): RpcRequestResult {
        return try {
            val response = room.localParticipant.performRpc(destination, method, payload)
            RpcRequestResult.Success(response)
        } catch (e: RpcError) {
            RpcRequestResult.Error(e.code, e.message)
        } catch (e: Throwable) {
            RpcRequestResult.Error(null, e.message ?: e.toString())
        }
    }

    fun toggleSubscriptionPermissions() {
        mutablePermissionAllowed.value = !mutablePermissionAllowed.value
        room.localParticipant.setTrackSubscriptionPermissions(mutablePermissionAllowed.value)
    }

    // Debug functions
    fun simulateMigration() {
        room.sendSimulateScenario(Room.SimulateScenario.MIGRATION)
    }

    fun simulateNodeFailure() {
        room.sendSimulateScenario(Room.SimulateScenario.NODE_FAILURE)
    }

    fun simulateServerLeaveFullReconnect() {
        room.sendSimulateScenario(Room.SimulateScenario.SERVER_LEAVE_FULL_RECONNECT)
    }

    fun updateAttribute(key: String, value: String) {
        room.localParticipant.updateAttributes(mapOf(key to value))
    }

    fun reconnect() {
        LKLog.e { "Reconnecting." }
        mutableConnectionStatus.value = "Reconnecting"
        mutablePrimarySpeaker.value = null
        room.disconnect()
        viewModelScope.launch(Dispatchers.IO) {
            connectToRoom()
        }
    }

    private suspend fun StressTest.SwitchRoom.execute() = coroutineScope {
        launch(Dispatchers.Default) {
            while (isActive) {
                delay(2000)
                dumpReferenceTables()
            }
        }

        while (isActive) {
            LKLog.d { "Stress test -> connect to first room" }
            launch(Dispatchers.IO) { quickConnectToRoom(firstToken) }
            delay(200)
            room.disconnect()
            delay(50)
            LKLog.d { "Stress test -> connect to second room" }
            launch(Dispatchers.IO) { quickConnectToRoom(secondToken) }
            delay(200)
            room.disconnect()
            delay(50)
        }
    }

    private suspend fun quickConnectToRoom(token: String) {
        try {
            room.connect(
                url = url,
                token = token,
            )
        } catch (e: Throwable) {
            LKLog.e(e) { "Failed to connect to room" }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun dumpReferenceTables() {
        try {
            val cls = Class.forName("android.os.Debug")
            val method = cls.getDeclaredMethod("dumpReferenceTables")
            val con = cls.getDeclaredConstructor().apply {
                isAccessible = true
            }
            method.invoke(con.newInstance())
        } catch (e: Exception) {
            LKLog.e(e) { "Unable to dump reference tables, you can try `adb shell settings put global hidden_api_policy 1`" }
        }
    }
}

private fun <T> LiveData<T>.hide(): LiveData<T> = this
private fun <T> MutableStateFlow<T>.hide(): StateFlow<T> = this

/** 麦克风轨道订阅信号缺失时的兜底时长。 */
private const val MEDIA_READY_FALLBACK_MS = 5_000L

data class RpcInvocationRecord(
    val timestamp: Long,
    val caller: Participant.Identity,
    val payload: String,
)

class RpcHandlerState(
    val method: String,
    val staticResponse: MutableStateFlow<String>,
    val invocations: MutableStateFlow<List<RpcInvocationRecord>>,
)

sealed class RpcRequestResult {
    data class Success(val response: String) : RpcRequestResult()
    data class Error(val code: Int?, val message: String) : RpcRequestResult()
}
