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

package io.livekit.android

import io.livekit.android.room.ProtocolVersion
import io.livekit.android.room.Room
import livekit.LivekitTemptalk
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.SSLCertificateVerifier

/**
 * Options for using with [Room.connect].
 */
data class ConnectOptions(
    /** Auto subscribe to room tracks upon connect, defaults to true */
    val autoSubscribe: Boolean = true,

    /**
     * A user-provided list of ice servers. This will be merged into
     * the ice servers in [rtcConfig] if it is also provided.
     */
    val iceServers: List<PeerConnection.IceServer>? = null,

    /**
     * A user-provided RTCConfiguration to override options.
     *
     * Note: LiveKit requires [PeerConnection.SdpSemantics.UNIFIED_PLAN] and
     * [PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY].
     */
    val rtcConfig: PeerConnection.RTCConfiguration? = null,
    /**
     * capture and publish audio track on connect, defaults to false
     */
    val audio: Boolean = false,
    /**
     * capture and publish video track on connect, defaults to false
     */
    val video: Boolean = false,

    /**
     * the protocol version to use with the server.
     */
    val protocolVersion: ProtocolVersion = ProtocolVersion.v13,

    val ttCallRequest: LivekitTemptalk.TTCallRequest? = null,

    val userAgent: String? = null,

    /**
     * Use ttsignal for transport, defaults to false
     */
    val useQuicSignal: Boolean = false,

    /**
     * Device type for QUIC signaling, where 1 = phone and 2 = PC.
     * Only used when [useQuicSignal] is true.
     */
    val quicDeviceType: Int = 0,

    /**
     * CID tag for QUIC signaling.
     * Only used when [useQuicSignal] is true.
     */
    val quicCidTag: String = "",

    /**
     * Self-signed root CA certificate in PEM format. When provided, TLS handshakes
     * will verify the server certificate chain against this CA for both WebSocket
     * and QUIC transports. When `null` or empty, the default system trust store is used.
     *
     * - **WebSocket**: the URL must use a domain name (not IP); the custom CA is used
     *   for certificate chain verification only.
     * - **QUIC**: supports both domain and IP-direct connections. When using IP-direct,
     *   set [serverHost] as well.
     *
     * Example:
     * ```
     * // QUIC IP-direct + self-signed cert
     * val options = ConnectOptions(
     *     useQuicSignal = true,
     *     caCertPem = rootCaPemString,
     *     serverHost = "real.domain.com",
     * )
     * room.connect("wss://1.2.3.4:443", token, options)
     *
     * // WebSocket domain + self-signed cert
     * val options = ConnectOptions(
     *     caCertPem = rootCaPemString,
     * )
     * room.connect("wss://real.domain.com:443", token, options)
     * ```
     */
    val caCertPem: String? = null,

    /**
     * Logical hostname for TLS SNI and certificate hostname verification in
     * QUIC IP-direct scenarios. Only used when [useQuicSignal] is true and the
     * connection URL contains an IP address instead of a domain name.
     * When `null` or empty, the host from the connection URL is used.
     *
     * This parameter is ignored for WebSocket connections.
     */
    val serverHost: String? = null,

    /**
     * Per-connection outbound proxy for the QUIC signaling transport
     * (RFC 9298 CONNECT-UDP / MASQUE). When set, the QUIC connection is
     * tunnelled through the proxy; the outer-hop TLS trust is configured
     * independently via [quicProxyCaCertPem]. Only used when [useQuicSignal]
     * is true. A raw proxy URL (e.g. "masque://proxy.example.com:443") or the
     * split host/port fields may be supplied.
     */
    val quicProxyUrl: String? = null,
    val quicProxyHost: String? = null,
    val quicProxyPort: Int = 0,
    val quicProxySni: String? = null,

    /**
     * Outer-hop (proxy) CA certificate in PEM format, separate from the inner
     * SFU [caCertPem]. When empty, the proxy certificate is accepted unverified
     * (acceptable for a self-signed Mode-B proxy on a trusted path); supply this
     * to enforce verification of the proxy's TLS certificate.
     */
    val quicProxyCaCertPem: String? = null,

    /**
     * Outer-hop (proxy) SPKI pin: base64 SHA-256 of the proxy leaf's
     * SubjectPublicKeyInfo. When set, the QUIC-over-proxy OUTER hop pins the
     * proxy's TLS certificate to this value (the same pin used for the WSS
     * tunnel and coturn TURN-TLS), instead of CA-chain verification. The inner
     * connection (client↔SFU) is unaffected and keeps verifying via [caCertPem].
     */
    val quicProxySpkiPin: String? = null,

    /**
     * Custom verifier for the TURN-TLS (transport) certificate of an ICE relay
     * server, enabling SPKI certificate pinning of the outer `turns:` TLS layer.
     * When provided, the PeerConnection is constructed with a
     * [livekit.org.webrtc.PeerConnectionDependencies] carrying this verifier, and
     * the native stack invokes [SSLCertificateVerifier.verify] with the peer leaf
     * certificate (X.509 DER) during the TURN TLS handshake. When `null` or empty,
     * the default behavior is used and no custom verification is performed.
     *
     * Unlike [caCertPem] (which verifies the **signaling** WebSocket/QUIC transport),
     * this applies to the **media** TURN PeerConnection transport. Media itself stays
     * DTLS-SRTP end-to-end; this only hardens the TURN transport-camouflage TLS.
     */
    val sslCertificateVerifier: SSLCertificateVerifier? = null,
) {
    internal var reconnect: Boolean = false
    internal var participantSid: String? = null
}
