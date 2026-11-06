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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuicConnectTimeoutTest {
    @Test
    fun `accepts integer timeouts in the inclusive range`() {
        assertEquals(1000, parseQuicConnectTimeoutMs("1000"))
        assertEquals(7000, parseQuicConnectTimeoutMs("7000"))
        assertEquals(15000, parseQuicConnectTimeoutMs("15000"))
    }

    @Test
    fun `rejects empty and non-integer timeouts`() {
        assertNull(parseQuicConnectTimeoutMs(""))
        assertNull(parseQuicConnectTimeoutMs("abc"))
        assertNull(parseQuicConnectTimeoutMs("3000.5"))
    }

    @Test
    fun `rejects timeouts outside the inclusive range`() {
        assertNull(parseQuicConnectTimeoutMs("999"))
        assertNull(parseQuicConnectTimeoutMs("15001"))
    }
}
