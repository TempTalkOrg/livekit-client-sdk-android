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
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * SPKI-pin algorithm used by the demo's self-hosted RTC proxy support.
 *
 * The pin is `base64(SHA-256(DER SubjectPublicKeyInfo))` of a leaf certificate's
 * public key, compared in constant time against the pin shipped in the proxy
 * share code. This mirrors the production TempTalk implementation so the same
 * coturn self-signed certificate (TURN-over-TLS, "turns:443" stealth) can be
 * pinned from the demo via [ProxyTurnTlsVerifier].
 */
object ProxySpki {

    /** `base64(SHA-256(cert.publicKey.encoded))` using [Base64.NO_WRAP]. */
    fun pinOf(cert: X509Certificate): String {
        // Fresh MessageDigest per call: MessageDigest is not documented thread-safe
        // and pinOf can run on the WebRTC network thread (via the TURN verifier).
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /**
     * True iff [cert]'s SPKI pin constant-time-equals [expectedPin]. The expected
     * pin is normalized first (see [normalizeBase64Pin]) so URL-safe / space-mangled
     * pins survive the paste/import path. Returns false on a blank pin (fail-closed).
     */
    fun matches(cert: X509Certificate, expectedPin: String): Boolean {
        val expected = normalizeBase64Pin(expectedPin)
        if (expected.isEmpty()) return false
        return constantTimeEquals(pinOf(cert), expected)
    }

    /**
     * Recovers a standard-base64 SPKI pin from a transported value. The pin is
     * compared as standard base64 (with `+` and `/`); transport along the paste /
     * URL path commonly turns `+` into a space, and a sender may emit URL-safe
     * base64 (`-` `_`). Since standard base64 contains none of those, mapping them
     * back is lossless.
     */
    fun normalizeBase64Pin(raw: String): String =
        raw.trim()
            .replace(' ', '+')
            .replace('-', '+')
            .replace('_', '/')

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var result = 0
        for (i in x.indices) result = result or (x[i].toInt() xor y[i].toInt())
        return result == 0
    }
}
