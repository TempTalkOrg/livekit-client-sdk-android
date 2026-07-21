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

/**
 * Helper for rewriting a direct-IP WebSocket URL to the real
 * [ConnectOptions.serverHost] domain when self-signed certificate
 * verification is in use.
 *
 * When QUIC verifies a self-signed cert against a direct-IP URL, the
 * WebSocket TLS handshake (okhttp/Conscrypt) cannot validate that IP host
 * against the server certificate (which is issued for the domain), so it
 * fails with `Trust anchor for certification path not found`. Rewriting the
 * host from the IP to [ConnectOptions.serverHost] lets the WebSocket TLS
 * handshake succeed.
 *
 * This must be applied on **every** WebSocket path that may receive a
 * direct-IP URL: both the QUIC->WebSocket fallback and the direct WebSocket
 * reconnect path used after QUIC is marked unhealthy.
 */
internal object WebSocketUrlRewriter {

    private val IPV4_REGEX = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$")

    /**
     * Returns [url] with its host rewritten from a direct IP to
     * [ConnectOptions.serverHost] when self-signed cert verification is in use.
     *
     * No-ops (returns [url] unchanged) when:
     * - [ConnectOptions.caCertPem] is null/empty (not using self-signed cert flow), or
     * - [ConnectOptions.serverHost] is null/blank, or
     * - the URL host is not an IP literal (already a domain), or
     * - the URL host already equals [ConnectOptions.serverHost].
     */
    fun rewriteIpUrlForWebSocket(url: String, options: ConnectOptions): String {
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
}
