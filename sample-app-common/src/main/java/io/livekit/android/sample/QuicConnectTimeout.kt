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

package io.livekit.android.sample

import io.livekit.android.ConnectOptions

const val DEFAULT_QUIC_CONNECT_TIMEOUT_MS = 7000
const val MIN_QUIC_CONNECT_TIMEOUT_MS = 1000
const val MAX_QUIC_CONNECT_TIMEOUT_MS = 15000

fun parseQuicConnectTimeoutMs(raw: String): Int? {
    val value = raw.toIntOrNull() ?: return null
    return value.takeIf { it in MIN_QUIC_CONNECT_TIMEOUT_MS..MAX_QUIC_CONNECT_TIMEOUT_MS }
}

internal fun ConnectOptions.withQuicConnectTimeout(configuredTimeoutMs: Int): ConnectOptions =
    copy(
        quicConnectTimeoutMs = if (useQuicSignal) configuredTimeoutMs else DEFAULT_QUIC_CONNECT_TIMEOUT_MS,
    )
