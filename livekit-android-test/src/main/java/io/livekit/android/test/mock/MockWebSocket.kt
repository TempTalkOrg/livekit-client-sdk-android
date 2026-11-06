/*
 * Copyright 2023-2026 LiveKit, Inc.
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

package io.livekit.android.test.mock

import io.livekit.android.ConnectOptions
import io.livekit.android.room.transport.SignalTransport
import okhttp3.Request
import okio.ByteString
import okio.IOException

class MockWebSocket(
    override val attemptId: Long,
    override val sendOnOpen: ByteString?,
    private val onConnect: (MockWebSocket, SignalTransport.Listener) -> Unit,
    private val onSend: ((ByteString) -> Unit)?,
) : SignalTransport {

    private lateinit var request: Request
    private lateinit var listener: SignalTransport.Listener

    var isClosed = false
        private set

    private val mutableSentRequests = mutableListOf<ByteString>()
    val sentRequests: List<ByteString>
        get() = mutableSentRequests

    override fun connect(url: String, token: String, options: ConnectOptions, listener: SignalTransport.Listener) {
        this.request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()
        this.listener = listener
        onConnect(this, listener)
    }

    fun request(): Request = request

    override fun cancel() {
        if (isClosed) {
            return
        }
        isClosed = true
        if (!::listener.isInitialized) {
            return
        }
        listener.onFailure(this, IOException("cancelled"), null)
    }

    override fun close(code: Int, reason: String) {
        if (isClosed) {
            return
        }
        isClosed = true
        if (!::listener.isInitialized) {
            return
        }
        listener.onClosing(this, code, reason)
        listener.onClosed(this, code, reason)
    }

    override fun send(data: ByteString): Boolean {
        if (isClosed) {
            return false
        }
        mutableSentRequests.add(data)
        onSend?.invoke(data)
        return !isClosed
    }

    fun clearRequests() {
        mutableSentRequests.clear()
    }
}
