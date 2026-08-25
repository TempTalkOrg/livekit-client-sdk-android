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

package io.livekit.android.sample

import io.livekit.android.sample.model.StressTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CallActivityBundleArgsTest {
    @Test
    fun `BundleArgs uses the default QUIC connect timeout`() {
        val args = createArgs()

        assertEquals(DEFAULT_QUIC_CONNECT_TIMEOUT_MS, args.quicConnectTimeoutMs)
    }

    @Test
    fun `BundleArgs preserves a custom QUIC connect timeout`() {
        val args = createArgs(quicConnectTimeoutMs = 9200)

        assertEquals(9200, args.quicConnectTimeoutMs)
    }

    private fun createArgs(
        quicConnectTimeoutMs: Int = DEFAULT_QUIC_CONNECT_TIMEOUT_MS,
    ) = CallActivity.BundleArgs(
        url = "wss://example.test",
        token = "token",
        e2eeKey = "",
        e2eeOn = false,
        quicOn = true,
        quicDeviceType = 1,
        quicCidTag = "",
        stressTest = StressTest.None,
        quicConnectTimeoutMs = quicConnectTimeoutMs,
    )
}
