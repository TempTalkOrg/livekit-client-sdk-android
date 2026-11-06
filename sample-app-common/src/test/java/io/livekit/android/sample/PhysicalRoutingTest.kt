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

import io.livekit.android.ConnectOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalRoutingTest {
    @Test
    fun `QUIC physical routing forwards the selected network handle`() {
        val options = ConnectOptions(useQuicSignal = true)
            .withPhysicalRouting(enabled = true, networkHandle = 123456L)

        assertTrue(options.forcePhysical)
        assertEquals(123456L, options.physicalNetworkHandle)
    }

    @Test
    fun `WebSocket ignores physical routing`() {
        val options = ConnectOptions(useQuicSignal = false)
            .withPhysicalRouting(enabled = true, networkHandle = 123456L)

        assertFalse(options.forcePhysical)
        assertEquals(0L, options.physicalNetworkHandle)
    }
}
