/*
 * Copyright 2025 LiveKit, Inc.
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

package io.livekit.android.room

import io.livekit.android.ConnectOptions
import io.livekit.android.test.MockE2ETest
import livekit.org.webrtc.MockPeerConnectionFactory
import livekit.org.webrtc.SSLCertificateVerifier
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies that [ConnectOptions.sslCertificateVerifier] is threaded through the
 * Dagger `@AssistedInject` chain into PeerConnection creation, and that the
 * backward-compat path (null verifier) is preserved.
 */
@RunWith(RobolectricTestRunner::class)
class RTCEngineSslVerifierMockE2ETest : MockE2ETest() {

    private val mockFactory: MockPeerConnectionFactory
        get() = component.peerConnectionFactory() as MockPeerConnectionFactory

    // S1 — a non-null verifier on ConnectOptions reaches PeerConnection creation
    // (i.e. the deps overload was taken and forwarded the verifier).
    @Test
    fun nonNullVerifierIsPassedToPeerConnection() = runTest {
        val sentinel = SSLCertificateVerifier { true }
        connect(connectOptions = ConnectOptions(sslCertificateVerifier = sentinel))
        assertSame(sentinel, mockFactory.lastSslCertificateVerifier)
    }

    // S2 — backward compat: with no verifier, PeerConnection creation receives null
    // (the unchanged no-deps overload path).
    @Test
    fun nullVerifierByDefault() = runTest {
        connect()
        assertNull(mockFactory.lastSslCertificateVerifier)
    }

    // S3 — ConnectOptions defaults the field to null (no behavior change for callers
    // that don't set it).
    @Test
    fun connectOptionsDefaultsVerifierToNull() {
        assertNull(ConnectOptions().sslCertificateVerifier)
    }
}
