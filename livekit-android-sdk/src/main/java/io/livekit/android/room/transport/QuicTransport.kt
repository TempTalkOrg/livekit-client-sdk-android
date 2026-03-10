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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.difft.android.smp.Config
import org.difft.android.smp.Connection
import org.difft.android.smp.Connector
import org.difft.android.smp.Const
import org.difft.android.smp.IConnectionHandler
import org.difft.android.smp.Stream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class QuicTransport(
    override val attemptId: Long,
    override val sendOnOpen: ByteString?,
    private val connector: Connector,
) : SignalTransport {
    private var connection: Connection? = null
    private var stream: Stream? = null
    private var listener: SignalTransport.Listener? = null

    private val lock = Any()

    private val listenerExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "QuicTransport-Listener-Thread-$attemptId").apply { isDaemon = true }
        }

    companion object {
        init {
            try {
                System.loadLibrary("signal")
            } catch (e: UnsatisfiedLinkError) {
                LKLog.e(e) { "Failed to load ttsignaljni library." }
            }
        }
    }

    override fun connect(
        url: String,
        token: String,
        options: ConnectOptions,
        listener: SignalTransport.Listener,
    ) {
        this.listener = listener
        val uri = Uri.parse(url)

        val config = Config().apply {
            idleTimeOut = 14000
            pingInterval = 7000
            hostname = uri.host
            port = if (uri.port != -1) uri.port else 443
            maxConnections = 1
            congestCtrl = Const.CC_BBR2
            pingOn = true
            // alpn = "h3" //for webtransport
            alpn = "ttsignal" // for raw quic
        }

        val connectionHandler = object : IConnectionHandler {
            override fun onConnectResult(conn: Connection?, errorCode: Int, message: String?) {
                LKLog.i { "[quic] onConnectResult: $errorCode, $message, ${this@QuicTransport}" }
                executeOnListenerThread {
                    if (errorCode == 0) {
                        synchronized(lock) {
                            this@QuicTransport.connection = conn
                        }
                    } else {
                        listener.onFailure(this@QuicTransport, TtsignalException("Connection failed: $message", errorCode), null)
                    }
                }
            }

            override fun onStreamCreated(conn: Connection?, stream: Stream) {
                LKLog.i { "[quic] onStreamCreated: ${stream.id()}, ${this@QuicTransport}" }
                executeOnListenerThread {
                    synchronized(lock) {
                        this@QuicTransport.stream = stream
                    }
                    listener.onOpen(this@QuicTransport)
                }
            }

            override fun onStreamClosed(conn: Connection?, stream: Stream) {
                LKLog.i { "[quic] onStreamClosed: ${stream.id()}, ${this@QuicTransport}" }
                executeOnListenerThread {
                    synchronized(lock) {
                        if (this@QuicTransport.stream?.id() == stream.id()) {
                            this@QuicTransport.stream = null
                        }
                    }
                }
            }

            override fun onRecvCmd(conn: Connection?, timestamp: Long, transId: Int, stream: Stream?, buffer: ByteArray) {
                val cmd = Charset.forName("UTF-8").decode(ByteBuffer.wrap(buffer)).toString()
                LKLog.v { "CLI command content: $cmd, ${this@QuicTransport}" }
            }

            override fun onRecvData(conn: Connection?, timestamp: Long, transId: Int, stream: Stream?, buffer: ByteArray) {
                executeOnListenerThread {
                    listener.onMessage(this@QuicTransport, buffer.toByteString())
                }
            }

            override fun onRestart(conn: Connection?, result: Int, address: String?) {
                LKLog.i { "[quic] Restart result: $result, address: $address, ${this@QuicTransport}" }
            }

            override fun onClosed(conn: Connection?, reason: String?) {
                LKLog.i { "[quic] onClosed: $reason, ${this@QuicTransport}" }
                executeOnListenerThread {
                    listener.onClosed(this@QuicTransport, 1000, reason ?: "Connection closed")
                    synchronized(lock) {
                        cleanup()
                    }
                }
            }

            override fun onException(conn: Connection?, errorMsg: String?) {
                LKLog.e { "[quic] onException: $errorMsg, ${this@QuicTransport}" }
                executeOnListenerThread {
                    listener.onFailure(this@QuicTransport, TtsignalException(errorMsg ?: "Unknown ttsignal exception"), null)
                    synchronized(lock) {
                        cleanup()
                    }
                }
            }
        }

        this.connection = connector.createConnection(config, connectionHandler)

        val authObject = buildJsonObject {
            if (token.isNotEmpty()) {
                put("Authorization", "Bearer $token")
            }
            options.userAgent?.let { userAgent ->
                if (userAgent.isNotEmpty()) {
                    put("User-Agent", userAgent)
                }
            }
        }

        val host = url.replaceFirst("wss", "https")
        val props = authObject.toString()
        LKLog.i { "[quic] connect: host=$host, props=$authObject, ${this@QuicTransport}" }
        this.connection?.connect(host, props, 7 * 1000)
    }

    override fun send(data: ByteString): Boolean {
        synchronized(lock) {
            val stream = this.stream ?: return false.also { LKLog.w { "send called but stream is not available., ${this@QuicTransport}" } }
            val bytes = data.toByteArray()
            return stream.sendData(bytes) == 0
        }
    }

    override fun close(code: Int, reason: String) {
        executeOnListenerThread {
            LKLog.i { "[quic] close connection..., ${this@QuicTransport}" }
            synchronized(lock) {
                connection?.close()
                cleanup()
            }
        }
    }

    override fun cancel() {
        executeOnListenerThread {
            LKLog.i { "[quic] cancel connection..., ${this@QuicTransport}" }
            synchronized(lock) {
                connection?.close()
                cleanup()
            }
        }
    }

    private fun cleanup() {
        connection = null
        stream = null
    }

    override fun toString(): String {
        return "${javaClass.simpleName}@${Integer.toHexString(hashCode())}(attemptId=$attemptId)"
    }

    private fun executeOnListenerThread(block: () -> Unit) {
        try {
            if (!listenerExecutor.isShutdown) {
                listenerExecutor.execute(block)
            } else {
                LKLog.w { "Listener executor is shutdown, cannot execute callback." }
            }
        } catch (e: RejectedExecutionException) {
            LKLog.w(e) { "Failed to execute callback on listener thread." }
        }
    }
}

class TtsignalException(message: String, val errorCode: Int? = null) : Exception(message)
