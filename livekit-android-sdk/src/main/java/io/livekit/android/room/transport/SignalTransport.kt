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
import okhttp3.Response
import okio.ByteString

/**
 * An interface for a transport layer that the SignalClient can use.
 * This allows for swappable underlying connection implementations (e.g. WebSocket, ttsignal).
 */
interface SignalTransport {

    val attemptId: Long
    val sendOnOpen: ByteString?

    fun interface Factory {
        fun create(
            options: ConnectOptions,
            attemptId: Long,
            sendOnOpen: ByteString?,
        ): SignalTransport
    }

    /**
     * A listener for transport-level events.
     */
    interface Listener {
        fun onOpen(transport: SignalTransport)
        fun onMessage(transport: SignalTransport, message: ByteString)
        fun onClosed(transport: SignalTransport, code: Int, reason: String)
        fun onClosing(transport: SignalTransport, code: Int, reason: String)
        fun onFailure(transport: SignalTransport, t: Throwable, response: Response?)
    }

    /**
     * Connects to the server.
     *
     * @param url The server URL, including query parameters.
     * @param token The connection token.
     * @param options Connection options.
     * @param listener The listener for events.
     */
    fun connect(
        url: String,
        token: String,
        options: ConnectOptions,
        listener: Listener,
    )

    /**
     * Sends data over the connection.
     * @return true if the message was successfully queued for sending.
     */
    fun send(data: ByteString): Boolean

    /**
     * Closes the connection.
     */
    fun close(code: Int, reason: String)

    /**
     * Immediately and violently release resources held by this transport, discarding any enqueued
     * messages.
     */
    fun cancel()
}
