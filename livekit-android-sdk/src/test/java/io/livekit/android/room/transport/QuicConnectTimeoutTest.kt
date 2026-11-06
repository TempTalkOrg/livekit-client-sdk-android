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

package io.livekit.android.room.transport

import io.livekit.android.ConnectOptions
import io.livekit.android.room.ProtocolVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class QuicConnectTimeoutTest {
    @Test
    fun `connect options default to seven seconds`() {
        assertEquals(7000, ConnectOptions().quicConnectTimeoutMs)
    }

    @Test
    fun `connect options preserve custom timeout through copy`() {
        val options = ConnectOptions(quicConnectTimeoutMs = 3000)

        assertEquals(3000, options.quicConnectTimeoutMs)
        assertEquals(options, ConnectOptions().copy(quicConnectTimeoutMs = 3000))
    }

    @Test
    fun `connect options preserve physical routing through copy`() {
        val options = ConnectOptions(
            forcePhysical = true,
            physicalNetworkHandle = 123456L,
        )

        assertEquals(true, options.forcePhysical)
        assertEquals(123456L, options.physicalNetworkHandle)
        assertEquals(options, options.copy())
    }

    @Test
    fun `existing positional arguments retain quic device type position`() {
        val options = ConnectOptions(
            true,
            null,
            null,
            false,
            false,
            ProtocolVersion.v13,
            null,
            null,
            true,
            2,
        )

        assertEquals(2, options.quicDeviceType)
        assertEquals(7000, options.quicConnectTimeoutMs)
    }

    @Test
    fun `normalization accepts inclusive timeout boundaries`() {
        assertEquals(1000, normalizeQuicConnectTimeoutMs(1000))
        assertEquals(15000, normalizeQuicConnectTimeoutMs(15000))
    }

    @Test
    fun `normalization falls back for out of range timeouts`() {
        assertEquals(7000, normalizeQuicConnectTimeoutMs(999))
        assertEquals(7000, normalizeQuicConnectTimeoutMs(15001))
    }
}
