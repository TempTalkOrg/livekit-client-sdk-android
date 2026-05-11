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
import io.livekit.android.util.LKLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class WebSocketTransport(
    override val attemptId: Long,
    override val sendOnOpen: ByteString?,
    private val okHttpClient: OkHttpClient,
) : SignalTransport, WebSocketListener() {

    private var ws: WebSocket? = null
    private var listener: SignalTransport.Listener? = null

    override fun connect(
        url: String,
        token: String,
        options: ConnectOptions,
        listener: SignalTransport.Listener,
    ) {
        this.listener = listener

        val client = configureClient(options)

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")

        options.userAgent?.let {
            if (it.isNotEmpty()) {
                requestBuilder.header("User-Agent", it)
            }
        }

        val request = requestBuilder.build()
        ws = client.newWebSocket(request, this@WebSocketTransport)
    }

    /**
     * Returns an [OkHttpClient] configured for self-signed certificate verification
     * when [ConnectOptions.caCertPem] is provided.
     *
     * A custom [X509TrustManager] is built that trusts only the given root CA(s).
     * If the PEM is malformed, falls back to the unmodified [okHttpClient] so that
     * the connection can still attempt (and fail with a clear TLS error) rather
     * than crashing.
     */
    private fun configureClient(options: ConnectOptions): OkHttpClient {
        val caCertPem = options.caCertPem
        if (caCertPem.isNullOrEmpty()) {
            return okHttpClient
        }

        val trustManager = try {
            buildTrustManager(caCertPem)
        } catch (e: Exception) {
            LKLog.e(e) { "Failed to parse caCertPem, falling back to default trust store." }
            return okHttpClient
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)

        return okHttpClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    /**
     * @throws java.security.cert.CertificateException if [caCertPem] is malformed.
     * @throws IllegalStateException if the platform TrustManagerFactory yields no [X509TrustManager].
     */
    private fun buildTrustManager(caCertPem: String): X509TrustManager {
        val certFactory = CertificateFactory.getInstance("X.509")
        val certs = certFactory.generateCertificates(
            ByteArrayInputStream(caCertPem.toByteArray(Charsets.UTF_8)),
        )
        require(certs.isNotEmpty()) { "caCertPem did not contain any valid certificates." }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        certs.forEachIndexed { i, cert -> keyStore.setCertificateEntry("ca$i", cert) }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)

        return tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("No X509TrustManager found from TrustManagerFactory.")
    }

    override fun send(data: ByteString): Boolean {
        return ws?.send(data) ?: false
    }

    override fun close(code: Int, reason: String) {
        ws?.close(code, reason)
    }

    override fun cancel() {
        ws?.cancel()
    }

    // -- WebSocketListener methods --

    override fun onOpen(webSocket: WebSocket, response: Response) {
        listener?.onOpen(this)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        listener?.onMessage(this, bytes)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        listener?.onClosing(this, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        listener?.onClosed(this, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        listener?.onFailure(this, t, response)
    }

    override fun toString(): String {
        return "${javaClass.simpleName}@${Integer.toHexString(hashCode())}(attemptId=$attemptId)"
    }
}
