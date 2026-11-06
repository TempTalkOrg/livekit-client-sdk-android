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
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.example.android.smp.Config
import org.example.android.smp.Connection
import org.example.android.smp.IConnectionHandler
import org.example.android.smp.Stream
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Each QUIC transport runs its callbacks on threads of its own. Those threads must end once the
 * transport has delivered its last callback: an idle one is a GC root, and through the transport's
 * listener chain (SignalClient -> RTCEngine -> Room) it kept every finished call's Room in memory,
 * which ran a long-lived process out of heap (Crashlytics f3a3b5fb, 59 such thread pairs).
 *
 * The native connection is faked: [CapturingConnectionFactory] hands out no Connection and keeps
 * the handler, so a test can play the native callbacks itself.
 */
@RunWith(RobolectricTestRunner::class)
class QuicTransportThreadLifecycleTest {

    private val url = "wss://signal.example.com/rtc"

    private class CapturingConnectionFactory : (Config, IConnectionHandler) -> Connection? {
        var handler: IConnectionHandler? = null

        override fun invoke(config: Config, handler: IConnectionHandler): Connection? {
            this.handler = handler
            return null
        }
    }

    private fun quicThreadName(attemptId: Long) = "QuicTransport-Listener-Thread-$attemptId"

    private fun fallbackThreadName(attemptId: Long) = "QuicFallback-Callback-$attemptId"

    private fun assertThreadEnds(name: String) {
        val threads = Thread.getAllStackTraces().keys.filter { it.name == name }
        threads.forEach { it.join(THREAD_EXIT_TIMEOUT_MS) }
        assertFalse("thread $name is still alive", threads.any { it.isAlive })
    }

    @Test
    fun `listener thread ends after the native connection closes`() {
        val attemptId = 910_001L
        val factory = CapturingConnectionFactory()
        val transport = QuicTransport(attemptId, null, factory)
        val listener = mock<SignalTransport.Listener>()

        transport.connect(url, "token", ConnectOptions(), listener)
        factory.handler!!.onClosed(null, "server close")

        verify(listener, timeout(CALLBACK_TIMEOUT_MS)).onClosed(eq(transport), eq(1000), any())
        assertThreadEnds(quicThreadName(attemptId))
    }

    @Test
    fun `cancel still delivers the native close before the listener thread ends`() {
        // SignalClient's ping timeout cancels the active transport and relies on the onClosed
        // that native sends afterwards to start reconnecting, so cancel() must not stop the thread.
        val attemptId = 910_002L
        val factory = CapturingConnectionFactory()
        val transport = QuicTransport(attemptId, null, factory)
        val listener = mock<SignalTransport.Listener>()

        transport.connect(url, "token", ConnectOptions(), listener)
        transport.cancel()
        factory.handler!!.onClosed(null, "local close")

        verify(listener, timeout(CALLBACK_TIMEOUT_MS)).onClosed(eq(transport), eq(1000), any())
        assertThreadEnds(quicThreadName(attemptId))
    }

    @Test
    fun `fallback wrapper threads end after an open QUIC connection closes`() {
        val attemptId = 910_003L
        val factory = CapturingConnectionFactory()
        val wrapper = QuicWithFallbackTransport(QuicTransport(attemptId, null, factory), mock<OkHttpClient>())
        val outer = mock<SignalTransport.Listener>()

        wrapper.connect(url, "token", ConnectOptions(), outer)
        factory.handler!!.onStreamCreated(null, Stream(null, 1))
        verify(outer, timeout(CALLBACK_TIMEOUT_MS)).onOpen(wrapper)
        factory.handler!!.onClosed(null, "server close")

        verify(outer, timeout(CALLBACK_TIMEOUT_MS)).onClosed(eq(wrapper), eq(1000), any())
        assertThreadEnds(quicThreadName(attemptId))
        assertThreadEnds(fallbackThreadName(attemptId))
    }

    @Test
    fun `fallback wrapper threads end after the WebSocket fallback fails`() {
        val attemptId = 910_004L
        val factory = CapturingConnectionFactory()
        val okHttpClient = mock<OkHttpClient>()
        whenever(okHttpClient.newWebSocket(any(), any<WebSocketListener>())).thenReturn(mock<WebSocket>())
        val wrapper = QuicWithFallbackTransport(QuicTransport(attemptId, null, factory), okHttpClient)
        val outer = mock<SignalTransport.Listener>()

        wrapper.connect(url, "token", ConnectOptions(), outer)
        // QUIC fails before open: the wrapper cancels it and connects over WebSocket instead.
        factory.handler!!.onConnectResult(null, 1, "connect timeout")
        val wsListener = argumentCaptor<WebSocketListener>()
        verify(okHttpClient, timeout(CALLBACK_TIMEOUT_MS)).newWebSocket(any(), wsListener.capture())
        // Native's close of the cancelled QUIC connection arrives late and is stale by now.
        factory.handler!!.onClosed(null, "local close")
        wsListener.firstValue.onFailure(mock<WebSocket>(), IOException("connection refused"), null)

        verify(outer, timeout(CALLBACK_TIMEOUT_MS)).onFailure(eq(wrapper), any(), anyOrNull())
        assertThreadEnds(quicThreadName(attemptId))
        assertThreadEnds(fallbackThreadName(attemptId))
    }

    @Test
    fun `fallback wrapper keeps forwarding until the active transport closes`() {
        // A QUIC failure after open is not the end: native still sends onClosed, which SignalClient
        // must see, so the wrapper thread may only end after it.
        val attemptId = 910_005L
        val factory = CapturingConnectionFactory()
        val wrapper = QuicWithFallbackTransport(QuicTransport(attemptId, null, factory), mock<OkHttpClient>())
        val outer = mock<SignalTransport.Listener>()

        wrapper.connect(url, "token", ConnectOptions(), outer)
        factory.handler!!.onStreamCreated(null, Stream(null, 1))
        verify(outer, timeout(CALLBACK_TIMEOUT_MS)).onOpen(wrapper)
        factory.handler!!.onException(null, "stream reset")
        verify(outer, timeout(CALLBACK_TIMEOUT_MS)).onFailure(eq(wrapper), any(), anyOrNull())
        factory.handler!!.onClosed(null, "local close")

        verify(outer, timeout(CALLBACK_TIMEOUT_MS)).onClosed(eq(wrapper), eq(1000), any())
        assertThreadEnds(quicThreadName(attemptId))
        assertThreadEnds(fallbackThreadName(attemptId))
    }

    private companion object {
        const val CALLBACK_TIMEOUT_MS = 5_000L
        const val THREAD_EXIT_TIMEOUT_MS = 5_000L
    }
}
