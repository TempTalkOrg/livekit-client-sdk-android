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

package io.livekit.android.room.metrics

import io.livekit.android.room.ConnectionState
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import livekit.LivekitMetrics.MetricLabel
import livekit.LivekitMetrics.MetricSample
import livekit.LivekitMetrics.MetricsBatch
import livekit.LivekitMetrics.TimeSeriesMetric
import livekit.LivekitModels.DataPacket
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsReport
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Handles getting the WebRTC metrics and sending them through the data channels.
 *
 * See [RTCMetric] for the related metrics we send.
 */
internal suspend fun collectMetrics(room: Room, rtcEngine: RTCEngine) = coroutineScope {
    launch { collectPublisherMetrics(room, rtcEngine) }
    launch { collectSubscriberMetrics(room, rtcEngine) }
}

private suspend fun collectPublisherMetrics(room: Room, rtcEngine: RTCEngine) {
    while (currentCoroutineContext().isActive) {
        delay(1000)
        // Skip stats collection while not in a steady connected state. During
        // RESUMING / RECONNECTING the underlying PeerConnection is .failed and
        // the synchronous getStats() BlockingCall just piles pressure onto the
        // WebRTC signaling/worker threads without producing actionable data.
        // See Docs/reconnect-metrics-storm-and-worker-crash-fix.md (Fix-8).
        if (rtcEngine.connectionState != ConnectionState.CONNECTED) continue
        val report = suspendCancellableCoroutine { cont ->
            room.getPublisherRTCStats { cont.resume(it) }
        }

        val strings = mutableListOf<String>()
        val stats = findPublisherVideoStats(strings, room, report, room.localParticipant.identity) +
            findPublisherAudioStats(strings, room, report, room.localParticipant.identity) +
            findPublisherStreamRttStats(strings, room, report, room.localParticipant.identity) +
            findConnectionRttStats(
                strings = strings,
                report = report,
                participantIdentity = room.localParticipant.identity,
                label = RTCMetric.PUBLISHER_RTT,
            )

        val dataPacket = with(DataPacket.newBuilder()) {
            metrics = with(MetricsBatch.newBuilder()) {
                timestampMs = report.timestampUs.microToMilli()
                addAllStrData(strings)
                addAllTimeSeries(stats)
                build()
            }
            kind = DataPacket.Kind.RELIABLE
            build()
        }

        try {
            val result = rtcEngine.sendData(dataPacket)
            result.exceptionOrNull()?.let {
                throw it
            }
        } catch (ignored: Exception) {
        }
    }
}

private suspend fun collectSubscriberMetrics(room: Room, rtcEngine: RTCEngine) {
    while (currentCoroutineContext().isActive) {
        delay(1000)
        // See collectPublisherMetrics above for rationale (Fix-8).
        if (rtcEngine.connectionState != ConnectionState.CONNECTED) continue
        val report = suspendCancellableCoroutine { cont ->
            room.getSubscriberRTCStats { cont.resume(it) }
        }

        val strings = mutableListOf<String>()
        val stats = findSubscriberAudioStats(strings, report, room.localParticipant.identity) +
            findSubscriberVideoStats(strings, report, room.localParticipant.identity) +
            findSubscriberStreamRttStats(strings, report, room.localParticipant.identity) +
            findConnectionRttStats(
                strings = strings,
                report = report,
                participantIdentity = room.localParticipant.identity,
                label = RTCMetric.SUBSCRIBER_RTT,
            ) +
            findSubscriberNetworkStats(strings, report, room.localParticipant.identity)

        val dataPacket = with(DataPacket.newBuilder()) {
            metrics = with(MetricsBatch.newBuilder()) {
                timestampMs = report.timestampUs.microToMilli()
                addAllStrData(strings)
                addAllTimeSeries(stats)
                build()
            }
            kind = DataPacket.Kind.RELIABLE
            build()
        }

        try {
            val result = rtcEngine.sendData(dataPacket)
            result.exceptionOrNull()?.let {
                throw it
            }
        } catch (ignored: Exception) {
        }
    }
}

private fun findPublisherVideoStats(strings: MutableList<String>, room: Room, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
    val mediaSources = report.statsMap
        .values
        .filter { stat -> stat.type == "media-source" && stat.members["kind"] == "video" }
    val videoTracks = report.statsMap
        .values
        .filter { stat -> stat.type == "outbound-rtp" && stat.members["kind"] == "video" }
        .mapNotNull { stat -> stat to getPublishTrackSid(room, mediaSources, stat) }

    val metrics = videoTracks
        .flatMap { (stat, trackSid) ->
            val rid = stat.members["rid"] as? String
            val qualityLimitMetrics = (stat.members["qualityLimitationDurations"] as? Map<*, *>)
                ?.let { durations ->
                    qualityLimitations.mapNotNull { (label, key) ->
                        val duration = durations[key] as? Number ?: return@mapNotNull null
                        createTimeSeriesForMetricValue(
                            stat = stat,
                            metric = label,
                            value = duration,
                            strings = strings,
                            identity = participantIdentity,
                            trackSid = trackSid,
                            rid = rid,
                        )
                    }
                }
                ?: emptyList()

            val publisherMetrics = listOf(
                RTCMetric.VIDEO_PACKETS_SENT,
                RTCMetric.VIDEO_BYTES_SENT,
                RTCMetric.VIDEO_RETRANSMITTED_PACKETS_SENT,
                RTCMetric.VIDEO_RETRANSMITTED_BYTES_SENT,
                RTCMetric.VIDEO_TARGET_BITRATE,
                RTCMetric.VIDEO_FRAMES_ENCODED,
                RTCMetric.VIDEO_KEY_FRAMES_ENCODED,
                RTCMetric.VIDEO_FRAMES_SENT,
                RTCMetric.VIDEO_HUGE_FRAMES_SENT,
                RTCMetric.VIDEO_FRAME_WIDTH,
                RTCMetric.VIDEO_FRAME_HEIGHT,
                RTCMetric.VIDEO_FRAMES_PER_SECOND,
                RTCMetric.VIDEO_TOTAL_ENCODE_TIME,
                RTCMetric.VIDEO_TOTAL_PACKET_SEND_DELAY,
                RTCMetric.VIDEO_PLI_COUNT,
                RTCMetric.VIDEO_FIR_COUNT,
                RTCMetric.VIDEO_NACK_COUNT,
                RTCMetric.VIDEO_QP_SUM,
                RTCMetric.VIDEO_QUALITY_LIMITATION_RESOLUTION_CHANGES,
            ).mapNotNull { metric ->
                createTimeSeriesForMetric(
                    stat = stat,
                    metric = metric,
                    strings = strings,
                    identity = participantIdentity,
                    trackSid = trackSid,
                    rid = rid,
                )
            }

            qualityLimitMetrics + publisherMetrics
        }

    return metrics
}

private fun findPublisherAudioStats(strings: MutableList<String>, room: Room, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
    val mediaSources = report.statsMap
        .values
        .filter { stat -> stat.type == "media-source" && stat.members["kind"] == "audio" }
    val audioTracks = report.statsMap
        .values
        .filter { stat -> stat.type == "outbound-rtp" && stat.members["kind"] == "audio" }
        .mapNotNull { stat -> stat to getPublishTrackSid(room, mediaSources, stat) }

    return audioTracks.flatMap { (stat, trackSid) ->
        listOf(
            RTCMetric.AUDIO_PACKETS_SENT,
            RTCMetric.AUDIO_BYTES_SENT,
            RTCMetric.AUDIO_RETRANSMITTED_PACKETS_SENT,
            RTCMetric.AUDIO_RETRANSMITTED_BYTES_SENT,
            RTCMetric.AUDIO_TARGET_BITRATE,
            RTCMetric.AUDIO_TOTAL_PACKET_SEND_DELAY,
        ).mapNotNull { metric ->
            createTimeSeriesForMetric(
                stat = stat,
                metric = metric,
                strings = strings,
                identity = participantIdentity,
                trackSid = trackSid,
            )
        }
    }
}

/**
 * The track sid isn't available on outbound-rtp stats, so we cross-reference against
 * the MediaSource trackIdentifier (which is a locally generated id), and then look up
 * the local published track for the sid.
 */
private fun getPublishTrackSid(room: Room, mediaSources: List<RTCStats>, track: RTCStats): String? {
    val mediaSourceId = track.members["mediaSourceId"] ?: return null
    val mediaSource = mediaSources.firstOrNull { m -> m.id == mediaSourceId } ?: return null
    val trackIdentifier = mediaSource.members["trackIdentifier"] ?: return null

    val publication = room.localParticipant.trackPublications.values
        .firstOrNull { publication -> publication.track?.rtcTrack?.id() == trackIdentifier } ?: return null

    return publication.sid
}

private fun findConnectionRttStats(
    strings: MutableList<String>,
    report: RTCStatsReport,
    participantIdentity: Participant.Identity?,
    label: RTCMetric,
): List<TimeSeriesMetric> {
    val selectedCandidatePair = findSelectedCandidatePair(report) ?: return emptyList()

    return listOfNotNull(
        createTimeSeriesForMetric(
            stat = selectedCandidatePair,
            metric = label,
            strings = strings,
            identity = participantIdentity,
            includeTrackSid = false,
        ),
    )
}

private fun findPublisherStreamRttStats(strings: MutableList<String>, room: Room, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
    val mediaSources = report.statsMap
        .values
        .filter { stat -> stat.type == "media-source" }

    return report.statsMap.values
        .filter { stat -> stat.type == "remote-inbound-rtp" }
        .mapNotNull { remoteStat ->
            val localId = remoteStat.members["localId"] as? String ?: return@mapNotNull null
            val outboundStat = report.statsMap[localId] ?: return@mapNotNull null
            val trackSid = getPublishTrackSid(room, mediaSources, outboundStat)
            createTimeSeriesForMetric(
                stat = remoteStat,
                metric = RTCMetric.PUBLISHER_STREAM_RTT,
                strings = strings,
                identity = participantIdentity,
                trackSid = trackSid,
                rid = outboundStat.members["rid"] as? String,
            )
        }
}

private fun findSubscriberStreamRttStats(strings: MutableList<String>, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
    return report.statsMap.values
        .filter { stat -> stat.type == "remote-outbound-rtp" }
        .mapNotNull { remoteStat ->
            val localId = remoteStat.members["localId"] as? String ?: return@mapNotNull null
            val inboundStat = report.statsMap[localId] ?: return@mapNotNull null
            createTimeSeriesForMetric(
                stat = remoteStat,
                metric = RTCMetric.SUBSCRIBER_STREAM_RTT,
                strings = strings,
                identity = participantIdentity,
                trackSid = inboundStat.members["trackIdentifier"] as? String,
                rid = inboundStat.members["rid"] as? String,
            )
        }
}

private fun findSubscriberNetworkStats(strings: MutableList<String>, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
    val selectedCandidatePair = findSelectedCandidatePair(report) ?: return emptyList()

    return listOf(
        RTCMetric.CURRENT_ROUND_TRIP_TIME,
        RTCMetric.AVAILABLE_INCOMING_BITRATE,
    ).mapNotNull { metric ->
        createTimeSeriesForMetric(
            stat = selectedCandidatePair,
            metric = metric,
            strings = strings,
            identity = participantIdentity,
            includeTrackSid = false,
        )
    }
}

private fun findSelectedCandidatePair(report: RTCStatsReport): RTCStats? {
    val selectedCandidatePairId = report.statsMap.values
        .firstOrNull { stat -> stat.type == "transport" }
        ?.members
        ?.get("selectedCandidatePairId") as? String

    if (selectedCandidatePairId != null) {
        report.statsMap[selectedCandidatePairId]?.let { return it }
    }

    return report.statsMap.values.firstOrNull { stat ->
        stat.type == "candidate-pair" &&
            stat.members["nominated"] == true &&
            stat.members["state"] == "succeeded"
    }
}

private fun findSubscriberAudioStats(strings: MutableList<String>, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
    val audioTracks = report.statsMap.filterValues { stat ->
        stat.type == "inbound-rtp" && stat.members["kind"] == "audio"
    }

    val metrics = audioTracks.values
        .flatMap { stat ->
            listOf(
                RTCMetric.CONCEALED_SAMPLES,
                RTCMetric.CONCEALMENT_EVENTS,
                RTCMetric.SILENT_CONCEALED_SAMPLES,
                RTCMetric.INTERRUPTION_COUNT,
                RTCMetric.TOTAL_INTERRUPTION_DURATION,
                RTCMetric.JITTER_BUFFER_DELAY,
                RTCMetric.JITTER_BUFFER_EMITTED_COUNT,
            ).mapNotNull { metric ->
                createTimeSeriesForMetric(
                    stat = stat,
                    metric = metric,
                    strings = strings,
                    identity = participantIdentity,
                )
            }
        }

    return metrics
}

private fun findSubscriberVideoStats(strings: MutableList<String>, report: RTCStatsReport, participantIdentity: Participant.Identity?): List<TimeSeriesMetric> {
    val videoTracks = report.statsMap.filterValues { stat ->
        stat.type == "inbound-rtp" && stat.members["kind"] == "video"
    }

    val metrics = videoTracks.values
        .flatMap { stat ->
            listOf(
                RTCMetric.FREEZE_COUNT,
                RTCMetric.TOTAL_FREEZES_DURATION,
                RTCMetric.PAUSE_COUNT,
                RTCMetric.TOTAL_PAUSES_DURATION,
                RTCMetric.JITTER_BUFFER_DELAY,
                RTCMetric.JITTER_BUFFER_EMITTED_COUNT,
                RTCMetric.PACKETS_RECEIVED,
                RTCMetric.BYTES_RECEIVED,
                RTCMetric.PACKETS_LOST,
                RTCMetric.JITTER,
                RTCMetric.FRAMES_RECEIVED,
                RTCMetric.FRAMES_DECODED,
                RTCMetric.KEY_FRAMES_DECODED,
                RTCMetric.FRAMES_DROPPED,
                RTCMetric.FRAME_WIDTH,
                RTCMetric.FRAME_HEIGHT,
                RTCMetric.FRAMES_PER_SECOND,
                RTCMetric.JITTER_BUFFER_TARGET_DELAY,
                RTCMetric.JITTER_BUFFER_MINIMUM_DELAY,
                RTCMetric.TOTAL_DECODE_TIME,
                RTCMetric.TOTAL_PROCESSING_DELAY,
                RTCMetric.TOTAL_ASSEMBLY_TIME,
                RTCMetric.PLI_COUNT,
                RTCMetric.FIR_COUNT,
                RTCMetric.NACK_COUNT,
            ).mapNotNull { metric ->
                createTimeSeriesForMetric(
                    stat = stat,
                    metric = metric,
                    strings = strings,
                    identity = participantIdentity,
                )
            }
        }

    return metrics
}

// Utility methods

/**
 * Gets the final index to use for indexes pointing at the MetricsBatch.str_data.
 * Index starts at [MetricLabel.METRIC_LABEL_PREDEFINED_MAX_VALUE].
 *
 * Receivers should parse index values like so:
 * ```
 * if index < LABEL_MAX_VALUE
 *    MetricLabel[index]
 * else
 *    str_data[index - 4096]
 * ```
 */
private fun MutableList<String>.getOrCreateIndex(string: String): Int {
    var index = indexOf(string)

    if (index == -1) {
        // Doesn't exist, create.
        add(string)
        index = size - 1
    }

    return index + MetricLabel.METRIC_LABEL_PREDEFINED_MAX_VALUE.number
}

private fun createMetricSample(
    timestampMs: Long,
    value: Number,
): MetricSample {
    return with(MetricSample.newBuilder()) {
        this.timestampMs = timestampMs
        this.value = value.toFloat()
        build()
    }
}

private fun createTimeSeriesForMetric(
    stat: RTCStats,
    metric: RTCMetric,
    strings: MutableList<String>,
    identity: Participant.Identity? = null,
    includeTrackSid: Boolean = true,
    trackSid: String? = null,
    rid: String? = stat.members["rid"] as? String,
): TimeSeriesMetric? {
    val value = stat.members[metric.statKey] as? Number ?: return null
    val resolvedTrackSid = trackSid ?: if (includeTrackSid) {
        stat.members["trackIdentifier"] as? String ?: return null
    } else {
        null
    }

    return createTimeSeriesForMetricValue(
        stat = stat,
        metric = metric,
        value = value,
        strings = strings,
        identity = identity,
        trackSid = resolvedTrackSid,
        rid = rid,
    )
}

private fun createTimeSeriesForMetricValue(
    stat: RTCStats,
    metric: RTCMetric,
    value: Number,
    strings: MutableList<String>,
    identity: Participant.Identity? = null,
    trackSid: String? = null,
    rid: String? = null,
): TimeSeriesMetric {
    val sample = createMetricSample(stat.timestampUs.microToMilli(), value)

    return createTimeSeries(
        label = metric.protoLabel,
        strings = strings,
        samples = listOf(sample),
        identity = identity,
        trackSid = trackSid,
        rid = rid,
    )
}

private fun createTimeSeries(
    label: MetricLabel,
    strings: MutableList<String>,
    samples: List<MetricSample>,
    identity: Participant.Identity? = null,
    trackSid: String? = null,
    rid: String? = null,
): TimeSeriesMetric {
    return with(TimeSeriesMetric.newBuilder()) {
        this.label = label.number

        if (identity != null) {
            this.participantIdentity = strings.getOrCreateIndex(identity.value)
        }
        if (trackSid != null) {
            this.trackSid = strings.getOrCreateIndex(trackSid)
        }

        if (rid != null) {
            this.rid = strings.getOrCreateIndex(rid)
        }
        this.addAllSamples(samples)
        build()
    }
}

private fun Number.microToMilli(): Long {
    return TimeUnit.MILLISECONDS.convert(this.toLong(), TimeUnit.MILLISECONDS)
}

private enum class RTCMetric(val protoLabel: MetricLabel, val statKey: String) {
    FREEZE_COUNT(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_FREEZE_COUNT, "freezeCount"),
    TOTAL_FREEZES_DURATION(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_TOTAL_FREEZE_DURATION, "totalFreezesDuration"),
    PAUSE_COUNT(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_PAUSE_COUNT, "pauseCount"),
    TOTAL_PAUSES_DURATION(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_TOTAL_PAUSES_DURATION, "totalPausesDuration"),

    CONCEALED_SAMPLES(MetricLabel.CLIENT_AUDIO_SUBSCRIBER_CONCEALED_SAMPLES, "concealedSamples"),
    SILENT_CONCEALED_SAMPLES(MetricLabel.CLIENT_AUDIO_SUBSCRIBER_SILENT_CONCEALED_SAMPLES, "silentConcealedSamples"),
    CONCEALMENT_EVENTS(MetricLabel.CLIENT_AUDIO_SUBSCRIBER_CONCEALMENT_EVENTS, "concealmentEvents"),
    INTERRUPTION_COUNT(MetricLabel.CLIENT_AUDIO_SUBSCRIBER_INTERRUPTION_COUNT, "interruptionCount"),
    TOTAL_INTERRUPTION_DURATION(MetricLabel.CLIENT_AUDIO_SUBSCRIBER_TOTAL_INTERRUPTION_DURATION, "totalInterruptionDuration"),

    JITTER_BUFFER_DELAY(MetricLabel.CLIENT_SUBSCRIBER_JITTER_BUFFER_DELAY, "jitterBufferDelay"),
    JITTER_BUFFER_EMITTED_COUNT(MetricLabel.CLIENT_SUBSCRIBER_JITTER_BUFFER_EMITTED_COUNT, "jitterBufferEmittedCount"),
    PACKETS_RECEIVED(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_PACKETS_RECEIVED, "packetsReceived"),
    BYTES_RECEIVED(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_BYTES_RECEIVED, "bytesReceived"),
    PACKETS_LOST(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_PACKETS_LOST, "packetsLost"),
    JITTER(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_JITTER, "jitter"),
    FRAMES_RECEIVED(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_FRAMES_RECEIVED, "framesReceived"),
    FRAMES_DECODED(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_FRAMES_DECODED, "framesDecoded"),
    KEY_FRAMES_DECODED(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_KEY_FRAMES_DECODED, "keyFramesDecoded"),
    FRAMES_DROPPED(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_FRAMES_DROPPED, "framesDropped"),
    FRAME_WIDTH(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_FRAME_WIDTH, "frameWidth"),
    FRAME_HEIGHT(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_FRAME_HEIGHT, "frameHeight"),
    FRAMES_PER_SECOND(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_FRAMES_PER_SECOND, "framesPerSecond"),
    JITTER_BUFFER_TARGET_DELAY(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_JITTER_BUFFER_TARGET_DELAY, "jitterBufferTargetDelay"),
    JITTER_BUFFER_MINIMUM_DELAY(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_JITTER_BUFFER_MINIMUM_DELAY, "jitterBufferMinimumDelay"),
    TOTAL_DECODE_TIME(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_TOTAL_DECODE_TIME, "totalDecodeTime"),
    TOTAL_PROCESSING_DELAY(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_TOTAL_PROCESSING_DELAY, "totalProcessingDelay"),
    TOTAL_ASSEMBLY_TIME(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_TOTAL_ASSEMBLY_TIME, "totalAssemblyTime"),
    PLI_COUNT(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_PLI_COUNT, "pliCount"),
    FIR_COUNT(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_FIR_COUNT, "firCount"),
    NACK_COUNT(MetricLabel.CLIENT_VIDEO_SUBSCRIBER_NACK_COUNT, "nackCount"),

    QUALITY_LIMITATION_DURATION_BANDWIDTH(MetricLabel.CLIENT_VIDEO_PUBLISHER_QUALITY_LIMITATION_DURATION_BANDWIDTH, "qualityLimitationDurations"),
    QUALITY_LIMITATION_DURATION_CPU(MetricLabel.CLIENT_VIDEO_PUBLISHER_QUALITY_LIMITATION_DURATION_CPU, "qualityLimitationDurations"),
    QUALITY_LIMITATION_DURATION_OTHER(MetricLabel.CLIENT_VIDEO_PUBLISHER_QUALITY_LIMITATION_DURATION_OTHER, "qualityLimitationDurations"),
    VIDEO_PACKETS_SENT(MetricLabel.CLIENT_VIDEO_PUBLISHER_PACKETS_SENT, "packetsSent"),
    VIDEO_BYTES_SENT(MetricLabel.CLIENT_VIDEO_PUBLISHER_BYTES_SENT, "bytesSent"),
    VIDEO_RETRANSMITTED_PACKETS_SENT(MetricLabel.CLIENT_VIDEO_PUBLISHER_RETRANSMITTED_PACKETS_SENT, "retransmittedPacketsSent"),
    VIDEO_RETRANSMITTED_BYTES_SENT(MetricLabel.CLIENT_VIDEO_PUBLISHER_RETRANSMITTED_BYTES_SENT, "retransmittedBytesSent"),
    VIDEO_TARGET_BITRATE(MetricLabel.CLIENT_VIDEO_PUBLISHER_TARGET_BITRATE, "targetBitrate"),
    VIDEO_FRAMES_ENCODED(MetricLabel.CLIENT_VIDEO_PUBLISHER_FRAMES_ENCODED, "framesEncoded"),
    VIDEO_KEY_FRAMES_ENCODED(MetricLabel.CLIENT_VIDEO_PUBLISHER_KEY_FRAMES_ENCODED, "keyFramesEncoded"),
    VIDEO_FRAMES_SENT(MetricLabel.CLIENT_VIDEO_PUBLISHER_FRAMES_SENT, "framesSent"),
    VIDEO_HUGE_FRAMES_SENT(MetricLabel.CLIENT_VIDEO_PUBLISHER_HUGE_FRAMES_SENT, "hugeFramesSent"),
    VIDEO_FRAME_WIDTH(MetricLabel.CLIENT_VIDEO_PUBLISHER_FRAME_WIDTH, "frameWidth"),
    VIDEO_FRAME_HEIGHT(MetricLabel.CLIENT_VIDEO_PUBLISHER_FRAME_HEIGHT, "frameHeight"),
    VIDEO_FRAMES_PER_SECOND(MetricLabel.CLIENT_VIDEO_PUBLISHER_FRAMES_PER_SECOND, "framesPerSecond"),
    VIDEO_TOTAL_ENCODE_TIME(MetricLabel.CLIENT_VIDEO_PUBLISHER_TOTAL_ENCODE_TIME, "totalEncodeTime"),
    VIDEO_TOTAL_PACKET_SEND_DELAY(MetricLabel.CLIENT_VIDEO_PUBLISHER_TOTAL_PACKET_SEND_DELAY, "totalPacketSendDelay"),
    VIDEO_PLI_COUNT(MetricLabel.CLIENT_VIDEO_PUBLISHER_PLI_COUNT, "pliCount"),
    VIDEO_FIR_COUNT(MetricLabel.CLIENT_VIDEO_PUBLISHER_FIR_COUNT, "firCount"),
    VIDEO_NACK_COUNT(MetricLabel.CLIENT_VIDEO_PUBLISHER_NACK_COUNT, "nackCount"),
    VIDEO_QP_SUM(MetricLabel.CLIENT_VIDEO_PUBLISHER_QP_SUM, "qpSum"),
    VIDEO_QUALITY_LIMITATION_RESOLUTION_CHANGES(MetricLabel.CLIENT_VIDEO_PUBLISHER_QUALITY_LIMITATION_RESOLUTION_CHANGES, "qualityLimitationResolutionChanges"),

    AUDIO_PACKETS_SENT(MetricLabel.CLIENT_AUDIO_PUBLISHER_PACKETS_SENT, "packetsSent"),
    AUDIO_BYTES_SENT(MetricLabel.CLIENT_AUDIO_PUBLISHER_BYTES_SENT, "bytesSent"),
    AUDIO_RETRANSMITTED_PACKETS_SENT(MetricLabel.CLIENT_AUDIO_PUBLISHER_RETRANSMITTED_PACKETS_SENT, "retransmittedPacketsSent"),
    AUDIO_RETRANSMITTED_BYTES_SENT(MetricLabel.CLIENT_AUDIO_PUBLISHER_RETRANSMITTED_BYTES_SENT, "retransmittedBytesSent"),
    AUDIO_TARGET_BITRATE(MetricLabel.CLIENT_AUDIO_PUBLISHER_TARGET_BITRATE, "targetBitrate"),
    AUDIO_TOTAL_PACKET_SEND_DELAY(MetricLabel.CLIENT_AUDIO_PUBLISHER_TOTAL_PACKET_SEND_DELAY, "totalPacketSendDelay"),

    PUBLISHER_RTT(MetricLabel.PUBLISHER_RTT, "currentRoundTripTime"),
    SUBSCRIBER_RTT(MetricLabel.SUBSCRIBER_RTT, "currentRoundTripTime"),
    PUBLISHER_STREAM_RTT(MetricLabel.CLIENT_PUBLISHER_STREAM_RTT, "roundTripTime"),
    SUBSCRIBER_STREAM_RTT(MetricLabel.CLIENT_SUBSCRIBER_STREAM_RTT, "roundTripTime"),
    CURRENT_ROUND_TRIP_TIME(MetricLabel.CLIENT_SUBSCRIBER_CURRENT_ROUND_TRIP_TIME, "currentRoundTripTime"),
    AVAILABLE_INCOMING_BITRATE(MetricLabel.CLIENT_SUBSCRIBER_AVAILABLE_INCOMING_BITRATE, "availableIncomingBitrate"),
}

private val qualityLimitations = listOf(
    RTCMetric.QUALITY_LIMITATION_DURATION_CPU to "cpu",
    RTCMetric.QUALITY_LIMITATION_DURATION_BANDWIDTH to "bandwidth",
    RTCMetric.QUALITY_LIMITATION_DURATION_OTHER to "other",
)
