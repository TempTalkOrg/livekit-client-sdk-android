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

import android.net.Uri
import io.livekit.android.ConnectOptions
import io.livekit.android.util.LKLog
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.ByteString
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Wraps a [QuicTransport] with automatic WebSocket fallback.
 *
 * When QUIC fires [SignalTransport.Listener.onFailure] or
 * [SignalTransport.Listener.onClosed] **before** [SignalTransport.Listener.onOpen],
 * the wrapper closes QUIC, creates a [WebSocketTransport] with the same
 * connection arguments, and connects through it transparently.
 *
 * All listener callbacks report this wrapper as the `transport` reference,
 * so [io.livekit.android.room.SignalClient]'s `isActiveTransport()` checks
 * remain valid after a fallback.
 */
class QuicWithFallbackTransport(
    private val quicTransport: QuicTransport,
    private val okHttpClient: OkHttpClient,
) : SignalTransport {

    override val attemptId: Long get() = quicTransport.attemptId
    override val sendOnOpen: ByteString? get() = quicTransport.sendOnOpen

    private val callbackExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "QuicFallback-Callback-${quicTransport.attemptId}").apply { isDaemon = true }
        }

    @Volatile
    private var active: SignalTransport = quicTransport

    @Volatile
    private var hasOpened = false

    @Volatile
    private var hasFallenBack = false

    @Volatile
    private var connectUrl: String? = null

    @Volatile
    private var connectToken: String? = null

    @Volatile
    private var connectOptions: ConnectOptions? = null

    @Volatile
    private var outerListener: SignalTransport.Listener? = null

    /**
     * The raw listener passed to the inner transports. Every callback is
     * re-dispatched onto [callbackExecutor] so that all state mutations
     * and [outerListener] invocations happen on the same single thread,
     * regardless of whether the active transport is QUIC or WebSocket.
     */
    private val innerListener = object : SignalTransport.Listener {
        override fun onOpen(transport: SignalTransport) = postCallback {
            hasOpened = true
            val source = sourceOf(transport)
            if (outerListener == null) {
                LKLog.w { "[transport] onOpen dropped: outerListener=null (source=$source)" }
            } else {
                LKLog.i { "[transport] onOpen -> outerListener (source=$source)" }
                outerListener?.onOpen(this@QuicWithFallbackTransport)
            }
        }

        override fun onMessage(transport: SignalTransport, message: ByteString) = postCallback {
            if (outerListener == null) {
                LKLog.w { "[transport] onMessage dropped: outerListener=null (source=${sourceOf(transport)}, bytes=${message.size})" }
            } else {
                outerListener?.onMessage(this@QuicWithFallbackTransport, message)
            }
        }

        override fun onFailure(transport: SignalTransport, t: Throwable, response: Response?) = postCallback {
            val source = sourceOf(transport)
            if (!hasOpened && !hasFallenBack && transport === quicTransport) {
                LKLog.i { "[transport] onFailure -> triggering fallback (source=$source, cause=${t.message})" }
                fallbackToWebSocket(t)
                return@postCallback
            }
            if (transport === quicTransport && hasFallenBack) {
                LKLog.d { "[transport] ignoring stale QUIC onFailure after fallback: ${t.message}" }
                return@postCallback
            }
            if (outerListener == null) {
                LKLog.w { "[transport] onFailure dropped: outerListener=null (source=$source, hasOpened=$hasOpened, hasFallenBack=$hasFallenBack)" }
            } else {
                LKLog.i { "[transport] onFailure -> outerListener (source=$source, hasOpened=$hasOpened, hasFallenBack=$hasFallenBack, response=$response)" }
                outerListener?.onFailure(this@QuicWithFallbackTransport, t, response)
            }
        }

        override fun onClosed(transport: SignalTransport, code: Int, reason: String) = postCallback {
            val source = sourceOf(transport)
            if (!hasOpened && !hasFallenBack && transport === quicTransport) {
                LKLog.i { "[transport] onClosed -> triggering fallback (source=$source, reason=$reason)" }
                fallbackToWebSocket(TtsignalException("QUIC closed before open: $reason"))
                return@postCallback
            }
            if (transport === quicTransport && hasFallenBack) {
                LKLog.d { "[transport] ignoring stale QUIC onClosed after fallback: $reason" }
                return@postCallback
            }
            if (outerListener == null) {
                LKLog.w { "[transport] onClosed dropped: outerListener=null (source=$source, code=$code, reason=$reason)" }
            } else {
                LKLog.i { "[transport] onClosed -> outerListener (source=$source, code=$code, reason=$reason, hasOpened=$hasOpened, hasFallenBack=$hasFallenBack)" }
                outerListener?.onClosed(this@QuicWithFallbackTransport, code, reason)
            }
        }

        override fun onClosing(transport: SignalTransport, code: Int, reason: String) = postCallback {
            val source = sourceOf(transport)
            if (transport === quicTransport && hasFallenBack) {
                LKLog.d { "[transport] ignoring stale QUIC onClosing after fallback: $reason" }
                return@postCallback
            }
            if (outerListener == null) {
                LKLog.w { "[transport] onClosing dropped: outerListener=null (source=$source, code=$code, reason=$reason)" }
            } else {
                LKLog.i { "[transport] onClosing -> outerListener (source=$source, code=$code, reason=$reason)" }
                outerListener?.onClosing(this@QuicWithFallbackTransport, code, reason)
            }
        }

        override fun onRestarted(transport: SignalTransport, result: Int, address: String?) = postCallback {
            val source = sourceOf(transport)
            if (outerListener == null) {
                LKLog.w { "[transport] onRestarted dropped: outerListener=null (source=$source, result=$result, address=$address)" }
            } else {
                LKLog.i { "[transport] onRestarted -> outerListener (source=$source, result=$result, address=$address)" }
                outerListener?.onRestarted(this@QuicWithFallbackTransport, result, address)
            }
        }
    }

    /**
     * Returns a short label identifying which inner transport fired the callback,
     * for log correlation. "QUIC" or "WS" for the two known transports; otherwise
     * the class's simple name.
     */
    private fun sourceOf(transport: SignalTransport): String {
        val activeTransport = active
        return when (transport) {
            quicTransport -> "QUIC"
            activeTransport -> if (activeTransport is WebSocketTransport) "WS" else activeTransport.javaClass.simpleName
            else -> transport.javaClass.simpleName
        }
    }

    /**
     * Called on [callbackExecutor]. Creates a [WebSocketTransport] and
     * schedules its connect on a subsequent callback-executor turn so that
     * any pending QUIC teardown callbacks (e.g. the "local close" onClosed
     * that XQUIC fires ~1-3 s after [QuicTransport.cancel]) can be drained
     * first and correctly dropped as stale. This keeps the callback queue
     * ordering clean and avoids interleaving WebSocket open/failure with
     * leftover QUIC signals.
     *
     * If [WebSocketTransport.connect] throws synchronously (malformed URL,
     * TLS/PEM parse failure, OkHttp internal errors, etc.), the exception
     * is surfaced to [outerListener] via [SignalTransport.Listener.onFailure]
     * on the same callback thread so downstream code (SignalClient) can fail
     * the join continuation promptly instead of hanging.
     */
    private fun fallbackToWebSocket(cause: Throwable) {
        hasFallenBack = true
        LKLog.w { "[transport] QUIC failed before open: ${cause.message}, falling back to WebSocket" }
        try {
            quicTransport.cancel()
        } catch (e: Exception) {
            LKLog.w(e) { "[transport] quicTransport.cancel() threw while falling back" }
        }
        val ws = WebSocketTransport(attemptId, sendOnOpen, okHttpClient)
        active = ws
        val fallbackUrl = rewriteIpUrlForWebSocket(connectUrl!!, connectOptions!!)
        val token = connectToken!!
        val options = connectOptions!!
        postCallback {
            try {
                LKLog.i { "[transport] starting WebSocket fallback connect url=$fallbackUrl" }
                ws.connect(fallbackUrl, token, options, innerListener)
            } catch (t: Throwable) {
                LKLog.e(t) { "[transport] WebSocket fallback connect threw synchronously" }
                try {
                    ws.cancel()
                } catch (e: Exception) {
                    LKLog.w(e) { "[transport] ws.cancel() threw after failed connect" }
                }
                outerListener?.onFailure(this@QuicWithFallbackTransport, t, null)
            }
        }
    }

    /**
     * When QUIC is using self-signed cert verification with a direct-IP URL,
     * WebSocket's TLS handshake cannot validate the IP host against the
     * server certificate (which is issued for the domain). In that case,
     * rewrite the URL's host from the IP to the real [ConnectOptions.serverHost]
     * domain so the WebSocket fallback can succeed.
     *
     * No-ops (returns [url] unchanged) when:
     * - [ConnectOptions.caCertPem] is null (not using self-signed cert flow), or
     * - [ConnectOptions.serverHost] is null/blank, or
     * - the URL host is not an IP literal (already a domain).
     */
    private fun rewriteIpUrlForWebSocket(url: String, options: ConnectOptions): String {
        val caCertPem = options.caCertPem
        val serverHost = options.serverHost
        if (caCertPem.isNullOrEmpty() || serverHost.isNullOrBlank()) {
            return url
        }
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            LKLog.w(e) { "[transport] fallback url parse failed, keeping original: $url" }
            return url
        }
        val host = uri.host
        if (host.isNullOrEmpty()) {
            LKLog.w { "[transport] fallback url has no host, keeping original: $url" }
            return url
        }
        if (!isIpLiteral(host)) {
            LKLog.d { "[transport] fallback url host is not an IP ($host), no rewrite needed" }
            return url
        }
        if (host.equals(serverHost, ignoreCase = true)) {
            return url
        }
        val rewritten = replaceHost(url, uri, serverHost)
        LKLog.i {
            "[transport] rewrite fallback url host from IP=$host to serverHost=$serverHost " +
                "(self-signed cert + direct IP)"
        }
        return rewritten
    }

    private fun replaceHost(originalUrl: String, uri: Uri, newHost: String): String {
        val scheme = uri.scheme ?: return originalUrl
        val port = uri.port
        val authorityBuilder = StringBuilder()
        uri.userInfo?.let { authorityBuilder.append(it).append('@') }
        authorityBuilder.append(newHost)
        if (port != -1) {
            authorityBuilder.append(':').append(port)
        }
        val path = uri.encodedPath ?: ""
        val query = uri.encodedQuery?.let { "?$it" } ?: ""
        val fragment = uri.encodedFragment?.let { "#$it" } ?: ""
        return "$scheme://$authorityBuilder$path$query$fragment"
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.startsWith("[") && host.endsWith("]")) return true
        if (IPV4_REGEX.matches(host)) return true
        if (host.contains(':')) return true
        return false
    }

    private companion object {
        private val IPV4_REGEX = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$")
    }

    override fun connect(
        url: String,
        token: String,
        options: ConnectOptions,
        listener: SignalTransport.Listener,
    ) {
        connectUrl = url
        connectToken = token
        connectOptions = options
        outerListener = listener
        quicTransport.connect(url, token, options, innerListener)
    }

    override fun send(data: ByteString): Boolean = active.send(data)

    override fun close(code: Int, reason: String) = active.close(code, reason)

    override fun cancel() = active.cancel()

    override fun restart(networkHandle: Long) {
        if (hasFallenBack) {
            LKLog.i { "[transport] QUIC restart skipped: already fallen back to WebSocket" }
            postCallback { outerListener?.onRestarted(this, -1, null) }
            return
        }
        active.restart(networkHandle)
    }

    private fun postCallback(block: () -> Unit) {
        try {
            if (!callbackExecutor.isShutdown) {
                callbackExecutor.execute(block)
            }
        } catch (e: RejectedExecutionException) {
            LKLog.w(e) { "[transport] failed to post callback on fallback thread" }
        }
    }

    override fun toString(): String {
        val activeTransport = active
        return "${javaClass.simpleName}@${Integer.toHexString(hashCode())}(attemptId=$attemptId, active=${activeTransport.javaClass.simpleName})"
    }
}
