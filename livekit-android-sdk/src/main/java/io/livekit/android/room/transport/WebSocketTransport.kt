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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class WebSocketTransport(
    override val attemptId: Long,
    override val sendOnOpen: ByteString?,
    private val websocketFactory: WebSocket.Factory,
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

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")

        options.userAgent?.let {
            if (it.isNotEmpty()) {
                requestBuilder.header("User-Agent", it)
            }
        }

        val request = requestBuilder.build()
        ws = websocketFactory.newWebSocket(request, this@WebSocketTransport)
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
