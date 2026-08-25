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

package io.livekit.android.room

import io.livekit.android.ConnectOptions
import io.livekit.android.room.transport.SignalTransport
import io.livekit.android.stats.NetworkInfo
import io.livekit.android.stats.NetworkType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SignalClientValidationTest {

    @Test
    fun `tokenless transport failure preserves exact error without HTTP validation`() = runTest {
        val transportFactory = FakeSignalTransportFactory()
        val okHttpClient = mock<OkHttpClient>()
        val listener = mock<SignalClient.Listener>()
        val client = createSignalClient(transportFactory, okHttpClient).apply {
            this.listener = listener
        }
        val transportFailure = TestSslHandshakeException("certificate rejected", Unit)

        val join = async {
            runCatching {
                client.join("https://signal.example.com", token = "")
            }.exceptionOrNull()
        }
        runCurrent()

        transportFactory.transport.fail(transportFailure)
        val joinFailure = join.await()

        assertSame(transportFailure, joinFailure)
        val listenerError = argumentCaptor<Throwable>()
        verify(listener).onError(listenerError.capture())
        assertSame(transportFailure, listenerError.firstValue)
        verify(okHttpClient, never()).newCall(any())
    }

    @Test
    fun `nonblank token HTTP 401 maps to no auth and performs validation`() = runTest {
        val transportFactory = FakeSignalTransportFactory()
        val okHttpClient = mock<OkHttpClient>()
        val validationCall = mock<Call>()
        val validationResponse = Response.Builder()
            .request(Request.Builder().url("https://signal.example.com/rtc/validate").build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("invalid token".toResponseBody())
            .build()
        whenever(okHttpClient.newCall(any())).thenReturn(validationCall)
        whenever(validationCall.execute()).thenReturn(validationResponse)
        val listener = mock<SignalClient.Listener>()
        val client = createSignalClient(transportFactory, okHttpClient).apply {
            this.listener = listener
        }

        val join = async {
            runCatching {
                client.join("https://signal.example.com", token = "valid-looking-token")
            }.exceptionOrNull()
        }
        runCurrent()

        transportFactory.transport.fail(SSLHandshakeException("certificate rejected"))
        val joinFailure = join.await()

        assertTrue(joinFailure is RoomException.NoAuthException)
        val listenerError = argumentCaptor<Throwable>()
        verify(listener).onError(listenerError.capture())
        assertTrue(listenerError.firstValue is RoomException.NoAuthException)
        val validationRequest = argumentCaptor<Request>()
        verify(okHttpClient).newCall(validationRequest.capture())
        assertEquals(
            "Bearer valid-looking-token",
            validationRequest.firstValue.header("Authorization"),
        )
    }

    @Test
    fun `nonblank token certificate failure preserves exact error without HTTP validation`() = runTest {
        val transportFactory = FakeSignalTransportFactory()
        val okHttpClient = mock<OkHttpClient>()
        val listener = mock<SignalClient.Listener>()
        val client = createSignalClient(transportFactory, okHttpClient).apply {
            this.listener = listener
        }
        val transportFailure = TestSslHandshakeException("handshake failed", Unit).apply {
            initCause(CertificateException("certificate rejected"))
        }

        val join = async {
            runCatching {
                client.join("https://signal.example.com", token = "refreshed-token")
            }.exceptionOrNull()
        }
        runCurrent()

        transportFactory.transport.fail(transportFailure)
        val joinFailure = join.await()

        assertSame(transportFailure, joinFailure)
        val listenerError = argumentCaptor<Throwable>()
        verify(listener).onError(listenerError.capture())
        assertSame(transportFailure, listenerError.firstValue)
        verify(okHttpClient, never()).newCall(any())
    }

    private fun createSignalClient(
        transportFactory: SignalTransport.Factory,
        okHttpClient: OkHttpClient,
    ): SignalClient {
        val networkInfo = mock<NetworkInfo>()
        whenever(networkInfo.getNetworkType()).thenReturn(NetworkType.UNKNOWN)
        return SignalClient(
            transportFactory = transportFactory,
            json = Json,
            okHttpClient = okHttpClient,
            ioDispatcher = StandardTestDispatcher(),
            networkInfo = networkInfo,
        )
    }

    private class FakeSignalTransportFactory : SignalTransport.Factory {
        lateinit var transport: FakeSignalTransport
            private set

        override fun create(
            options: ConnectOptions,
            attemptId: Long,
            sendOnOpen: ByteString?,
        ): SignalTransport {
            return FakeSignalTransport(attemptId, sendOnOpen).also {
                transport = it
            }
        }
    }

    private class FakeSignalTransport(
        override val attemptId: Long,
        override val sendOnOpen: ByteString?,
    ) : SignalTransport {
        private lateinit var listener: SignalTransport.Listener

        override fun connect(
            url: String,
            token: String,
            options: ConnectOptions,
            listener: SignalTransport.Listener,
        ) {
            this.listener = listener
        }

        fun fail(error: Throwable) {
            listener.onFailure(this, error, null)
        }

        override fun send(data: ByteString): Boolean = true

        override fun close(code: Int, reason: String) = Unit

        override fun cancel() = Unit
    }

    private class TestSslHandshakeException(
        message: String,
        @Suppress("UNUSED_PARAMETER") noCopyConstructor: Unit,
    ) : SSLHandshakeException(message)
}
