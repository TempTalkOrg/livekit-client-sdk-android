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

import io.livekit.android.util.LKLog
import livekit.org.webrtc.SSLCertificateVerifier
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * SPKI pin for the TURN outer-TLS (self-signed coturn, Mode B). Wired into
 * WebRTC via [io.livekit.android.ConnectOptions.sslCertificateVerifier], it is
 * invoked by the native stack once per TURN-TLS handshake with the peer LEAF
 * certificate in DER while connecting to `turns:<host>:443?transport=tcp`.
 *
 * Fail-closed: any parse error / mismatch / blank pin returns false and NEVER
 * throws (native code cannot unwind a Kotlin exception). Lightweight — a single
 * SHA-256 over the SPKI, no I/O, no blocking.
 *
 * [expectedPin] is captured at construction (from the proxy config) so the
 * verifier is immutable and thread-confined to the value read when the call
 * started. Media itself stays DTLS-SRTP end-to-end; this only hardens the TURN
 * transport-camouflage TLS.
 */
class ProxyTurnTlsVerifier(private val expectedPin: String) : SSLCertificateVerifier {

    override fun verify(certificate: ByteArray?): Boolean {
        return try {
            val der = certificate
            if (der == null || der.isEmpty()) {
                LKLog.w { "[Proxy] TURN TLS verify rejected: empty leaf DER" }
                return false
            }
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            val ok = ProxySpki.matches(cert, expectedPin)
            if (!ok) {
                LKLog.w { "[Proxy] TURN TLS verify rejected: SPKI pin mismatch (derLen=${der.size})" }
            }
            ok
        } catch (t: Throwable) {
            // Catch Throwable, not Exception: ClassCastException (non-X509),
            // CertificateException (bad DER), and any native-callback surprise all
            // fail closed. Must never propagate out of verify().
            LKLog.w { "[Proxy] TURN TLS verify rejected: parse failure ${t.javaClass.simpleName}" }
            false
        }
    }
}
