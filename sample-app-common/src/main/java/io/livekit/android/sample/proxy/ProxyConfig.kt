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

package io.livekit.android.sample.proxy

import android.util.Base64
import io.livekit.android.util.LKLog
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.SSLCertificateVerifier
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Demo descriptor for a self-hosted proxy (operator host, Mode B: IP + certificate
 * fingerprint). A single proxy server fronts both transports the LiveKit SDK can
 * route through it:
 * - **media**: relay-only ICE through `turns:[host]:[port]` (SPKI-pinned outer TLS),
 *   via [buildRtcConfig] + [createTurnTlsVerifier];
 * - **QUIC signaling**: MASQUE CONNECT-UDP through the same [host]/[port]/[outerSni]/
 *   [spkiPinBase64], via [io.livekit.android.ConnectOptions] `quicProxy*` fields.
 *
 * For media, passing our own ICE servers makes LiveKit ignore the server-advertised
 * ones (see `RTCEngine.makeRTCConfig`), and `RELAY` suppresses host/srflx candidates,
 * so the client's real IP never reaches the SFU. Media stays DTLS-SRTP end-to-end.
 */
data class ProxyConfig(
    /** Proxy host the client dials (typically an IP literal in Mode B). */
    val host: String,
    /** Outer TLS port; the "turns:443" stealth front uses 443. */
    val port: Int = DEFAULT_PORT,
    /** base64(SHA-256(DER SubjectPublicKeyInfo)) of the proxy's self-signed leaf cert. */
    val spkiPinBase64: String,
    /** coturn `static-auth-secret` (TURN REST API); enables the media relay. */
    val turnSecret: String,
    /** Optional decoy SNI for the outer handshake (camouflage only). */
    val sni: String? = null,
) {
    /** Whether this config carries everything needed to relay call media. */
    fun turnEnabled(): Boolean =
        host.isNotBlank() && turnSecret.isNotBlank() && spkiPinBase64.isNotBlank()

    /** Non-empty decoy SNI for the outer handshake; never the bare IP [host]. */
    fun outerSni(): String = sni?.takeIf { it.isNotBlank() } ?: DEFAULT_DECOY_SNI

    /**
     * Generates time-limited TURN REST credentials (coturn `use-auth-secret`):
     * `username = <unix-expiry>`, `password = base64(HMAC-SHA1(secret, username))`.
     * Returns null when [turnSecret] is absent. coturn validates expiry only at
     * allocation time, so an in-progress call keeps working past expiry.
     */
    fun turnCredentials(ttlSeconds: Long = DEFAULT_TURN_TTL_SECONDS): Pair<String, String>? {
        val secret = turnSecret.takeIf { it.isNotBlank() } ?: return null
        val expiry = (System.currentTimeMillis() / 1000L) + ttlSeconds
        val username = expiry.toString()
        return runCatching {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val raw = mac.doFinal(username.toByteArray(Charsets.UTF_8))
            username to Base64.encodeToString(raw, Base64.NO_WRAP)
        }.getOrNull()
    }

    /**
     * Builds a relay-only [PeerConnection.RTCConfiguration] forcing WebRTC media
     * through the operator's TURN server. Returns null when TURN is not configured
     * (the call then keeps stock direct-media behavior). Pass this into
     * [io.livekit.android.ConnectOptions.rtcConfig].
     */
    fun buildRtcConfig(): PeerConnection.RTCConfiguration? {
        if (!turnEnabled()) return null
        val (user, credential) = turnCredentials() ?: return null

        // Stealth: the ONLY relay candidate is turns:443 (TURN over TLS). Media is
        // wrapped in an ordinary-looking TLS flow to :443 so a DPI box never sees a
        // TURN/STUN handshake on 3478. TLS_CERT_POLICY_SECURE makes WebRTC invoke
        // the custom SSLCertificateVerifier (see createTurnTlsVerifier); the verifier
        // SPKI-pins the proxy's self-signed leaf. INSECURE_NO_CHECK is NOT used — it
        // would route the handshake through SSL_VERIFY_NONE and skip the verifier.
        val turnsUrl = "turns:$host:$port?transport=tcp"
        val iceServers = listOf(
            PeerConnection.IceServer.builder(listOf(turnsUrl))
                .setUsername(user)
                .setPassword(credential)
                .setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_SECURE)
                .createIceServer(),
        )
        return PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    /**
     * The SPKI-pinning verifier for the outer TURN TLS, tied to the SAME gate as
     * [buildRtcConfig] so the two can't drift. Returns null when TURN is not
     * configured. Pass this into [io.livekit.android.ConnectOptions.sslCertificateVerifier].
     */
    fun createTurnTlsVerifier(): SSLCertificateVerifier? {
        if (!turnEnabled()) return null
        return ProxyTurnTlsVerifier(spkiPinBase64)
    }

    /** Masks the secrets so an accidental log of this config does not leak them. */
    override fun toString(): String {
        val pinDigest = if (spkiPinBase64.length > 8) "${spkiPinBase64.take(8)}…" else "***"
        val turnTag = if (turnSecret.isBlank()) "<none>" else "<set>"
        return "ProxyConfig(host=$host, port=$port, spkiPinPrefix=$pinDigest, sni=$sni, turnSecret=$turnTag)"
    }

    companion object {
        const val DEFAULT_PORT = 443
        const val DEFAULT_DECOY_SNI = "www.bing.com"
        private const val DEFAULT_TURN_TTL_SECONDS = 24L * 60L * 60L

        /**
         * Builds a config from raw demo inputs, or null when the proxy is disabled
         * or required fields are missing. Logs (with secrets masked) when a relay
         * config is produced.
         */
        fun fromInputs(
            enabled: Boolean,
            host: String,
            port: Int,
            spkiPinBase64: String,
            turnSecret: String,
            sni: String? = null,
        ): ProxyConfig? {
            if (!enabled) return null
            val config = ProxyConfig(
                host = host.trim(),
                port = port,
                spkiPinBase64 = ProxySpki.normalizeBase64Pin(spkiPinBase64),
                turnSecret = turnSecret.trim(),
                sni = sni?.trim()?.takeIf { it.isNotBlank() },
            )
            if (!config.turnEnabled()) {
                LKLog.w { "[Proxy] proxy enabled but incomplete (need host + pin + TURN secret); ignoring" }
                return null
            }
            LKLog.i { "[Proxy] proxy enabled (media TURN relay + QUIC MASQUE): $config" }
            return config
        }
    }
}
