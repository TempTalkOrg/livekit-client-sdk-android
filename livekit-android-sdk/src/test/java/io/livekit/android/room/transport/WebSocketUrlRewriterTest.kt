/*
 * Copyright 2025-2026 LiveKit, Inc.
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebSocketUrlRewriterTest {

    private val caCertPem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
    private val serverHost = "signal.example.com"

    private fun options(
        caCertPem: String? = this.caCertPem,
        serverHost: String? = this.serverHost,
    ) = ConnectOptions(caCertPem = caCertPem, serverHost = serverHost)

    @Test
    fun rewritesIpv4HostToServerHost() {
        val url = "wss://203.0.113.10/rtc?protocol=13&reconnect=1"
        val result = WebSocketUrlRewriter.rewriteIpUrlForWebSocket(url, options())
        assertEquals(
            "wss://signal.example.com/rtc?protocol=13&reconnect=1",
            result,
        )
    }

    @Test
    fun preservesPortWhenRewriting() {
        val url = "wss://203.0.113.10:8443/rtc?x=1"
        val result = WebSocketUrlRewriter.rewriteIpUrlForWebSocket(url, options())
        assertEquals("wss://signal.example.com:8443/rtc?x=1", result)
    }

    @Test
    fun rewritesIpv6LiteralHost() {
        val url = "wss://[2001:db8::1]:443/rtc"
        val result = WebSocketUrlRewriter.rewriteIpUrlForWebSocket(url, options())
        assertTrue(result.startsWith("wss://signal.example.com"))
        assertFalse(result.contains("2001:db8"))
    }

    @Test
    fun leavesDomainHostUnchanged() {
        val url = "wss://already.domain.com/rtc?protocol=13"
        val result = WebSocketUrlRewriter.rewriteIpUrlForWebSocket(url, options())
        assertEquals(url, result)
    }

    @Test
    fun noOpWhenCaCertPemNull() {
        val url = "wss://203.0.113.10/rtc"
        val result = WebSocketUrlRewriter.rewriteIpUrlForWebSocket(url, options(caCertPem = null))
        assertEquals(url, result)
    }

    @Test
    fun noOpWhenCaCertPemEmpty() {
        val url = "wss://203.0.113.10/rtc"
        val result = WebSocketUrlRewriter.rewriteIpUrlForWebSocket(url, options(caCertPem = ""))
        assertEquals(url, result)
    }

    @Test
    fun noOpWhenServerHostNull() {
        val url = "wss://203.0.113.10/rtc"
        val result = WebSocketUrlRewriter.rewriteIpUrlForWebSocket(url, options(serverHost = null))
        assertEquals(url, result)
    }

    @Test
    fun noOpWhenServerHostBlank() {
        val url = "wss://203.0.113.10/rtc"
        val result = WebSocketUrlRewriter.rewriteIpUrlForWebSocket(url, options(serverHost = "   "))
        assertEquals(url, result)
    }

    @Test
    fun noOpWhenHostAlreadyEqualsServerHost() {
        val url = "wss://203.0.113.10/rtc"
        val result = WebSocketUrlRewriter.rewriteIpUrlForWebSocket(
            url,
            options(serverHost = "203.0.113.10"),
        )
        assertEquals(url, result)
    }

    @Test
    fun isIdempotentWhenAppliedTwice() {
        val url = "wss://203.0.113.10/rtc?protocol=13"
        val once = WebSocketUrlRewriter.rewriteIpUrlForWebSocket(url, options())
        val twice = WebSocketUrlRewriter.rewriteIpUrlForWebSocket(once, options())
        assertEquals(once, twice)
    }
}
