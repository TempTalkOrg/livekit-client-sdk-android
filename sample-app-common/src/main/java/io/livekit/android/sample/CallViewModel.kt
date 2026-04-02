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
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.datastream.incoming.TextStreamReceiver
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
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.sample.common.BuildConfig
import io.livekit.android.sample.model.StressTest
import io.livekit.android.sample.service.ForegroundService
import io.livekit.android.util.LKLog
import io.livekit.android.util.flow
import kotlinx.coroutines.Dispatchers
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
    val audioProcessorOptions: AudioProcessorOptions? = null,
    val stressTest: StressTest = StressTest.None,
) : AndroidViewModel(application) {

    private fun getE2EEOptions(): E2EEOptions? {
        var e2eeOptions: E2EEOptions? = null
        if (e2ee && e2eeKey != null) {
            e2eeOptions = E2EEOptions()
        }
        if (!BuildConfig.USE_MERGE_START_CALL) {
            e2eeOptions?.keyProvider?.setSharedKey(e2eeKey!!)
        }
        return e2eeOptions
    }

    private fun getConnectOptions(): ConnectOptions {
        if (!BuildConfig.USE_MERGE_START_CALL || BuildConfig.MERGE_START_CALL_PARAM.isNullOrBlank()) {
            return ConnectOptions(
                useQuicSignal = quic,
                quicDeviceType = quicDeviceType,
                quicCidTag = quicCidTag,
            )
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
        )
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
                isPortrait = true,
            ),
            videoTrackPublishDefaults = VideoTrackPublishDefaults(
                videoEncoding = VideoEncoding(3_000_000, 30),
                videoCodec = VideoCodec.VP8.codecName,
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
    )

    private var curretnRotation: Int? = null

    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null
    val audioHandler = room.audioHandler as AudioSwitchHandler

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

    init {

        CameraXHelper.createCameraProvider(ProcessLifecycleOwner.get()).let {
            if (it.isSupported(application)) {
                CameraCapturerUtils.registerCameraProvider(it)
                cameraProvider = it
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
                        }

                        is RoomEvent.Reconnecting -> {
                            LKLog.i { "Reconnecting" }
                            mutableConnectionStatus.value = "Reconnecting"
                        }

                        is RoomEvent.Reconnected -> {
                            LKLog.i { "Reconnected" }
                            mutableConnectionStatus.value = "Reconnected"
                        }

                        is RoomEvent.ParticipantDisconnected -> {
                            LKLog.i { "ParticipantDisconnected: ${it.participant.identity} ${it.participant.sid}" }
                        }

                        is RoomEvent.ParticipantConnected -> {
                            LKLog.i { "ParticipantConnected: ${it.participant.identity} ${it.participant.sid}" }
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
                            LKLog.e { "Room event: $it" }
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
