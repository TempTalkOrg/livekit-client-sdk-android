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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Regression test for the self-signed-cert direct-IP WebSocket bug.
 *
 * Repro of the original bug (from the felix-sound 2026-06-10 log): once QUIC was
 * marked unhealthy, the reconnect built a plain [WebSocketTransport] and connected
 * to the raw IP URL (e.g. `wss://203.0.113.10/...`). okhttp/Conscrypt then tried to
 * validate the self-signed certificate (issued for the domain) against the IP host
 * and failed with `Trust anchor for certification path not found`.
 *
 * These tests capture the [Request] handed to OkHttp from [WebSocketTransport.connect]
 * and assert the host. With the pre-fix code (which used `url` directly), the host
 * would be the raw IP and [connectRewritesDirectIpUrlToServerHostForSelfSignedCert]
 * fails — reproducing the bug. With the fix, the host is the configured serverHost.
 *
 * Note: a non-PEM [ConnectOptions.caCertPem] is intentionally used. It is non-empty
 * (so the rewrite is enabled) but fails to parse, so `configureClient` falls back to
 * the provided (mocked) [OkHttpClient], letting us capture the outgoing request.
 */
@RunWith(RobolectricTestRunner::class)
class WebSocketTransportSelfSignedReproTest {

    private val serverHost = "signal.example.com"
    private val nonParseableCaCertPem = "not-a-real-pem"

    private fun connectAndCaptureRequest(url: String, options: ConnectOptions): Request {
        val okHttpClient = mock<OkHttpClient>()
        whenever(okHttpClient.newWebSocket(any(), any<WebSocketListener>()))
            .thenReturn(mock<WebSocket>())

        val transport = WebSocketTransport(attemptId = 5L, sendOnOpen = null, okHttpClient = okHttpClient)
        transport.connect(url, "token", options, mock<SignalTransport.Listener>())

        val requestCaptor = argumentCaptor<Request>()
        verify(okHttpClient).newWebSocket(requestCaptor.capture(), any<WebSocketListener>())
        return requestCaptor.firstValue
    }

    /**
     * The bug scenario: self-signed cert + serverHost + direct-IP URL.
     * Fails on pre-fix code (host == raw IP), passes after the fix (host == serverHost).
     */
    @Test
    fun connectRewritesDirectIpUrlToServerHostForSelfSignedCert() {
        val request = connectAndCaptureRequest(
            url = "wss://203.0.113.10/rtc?protocol=13&reconnect=1",
            options = ConnectOptions(caCertPem = nonParseableCaCertPem, serverHost = serverHost),
        )

        assertEquals(serverHost, request.url.host)
        assertEquals("/rtc", request.url.encodedPath)
        assertEquals("protocol=13&reconnect=1", request.url.encodedQuery)
    }

    /**
     * Without self-signed cert config the URL must be left untouched (no serverHost
     * to rewrite to), so a plain domain WebSocket keeps connecting to its host.
     */
    @Test
    fun connectLeavesUrlUntouchedWithoutSelfSignedCert() {
        val request = connectAndCaptureRequest(
            url = "wss://signal.example.com/rtc?protocol=13",
            options = ConnectOptions(),
        )

        assertEquals("signal.example.com", request.url.host)
    }
}
