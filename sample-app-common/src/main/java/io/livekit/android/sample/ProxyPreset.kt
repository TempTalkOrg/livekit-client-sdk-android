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

import io.livekit.android.sample.proxy.ProxyConfig

/**
 * A selectable self-hosted proxy entry, defined in `local-config.json` under the
 * `proxies` key and surfaced in the connect screen as a dropdown. Selecting one
 * routes both call media (TURN relay) and QUIC signaling (MASQUE) through that
 * operator (see [ProxyConfig]).
 */
@kotlinx.serialization.Serializable
data class ProxyPreset(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = ProxyConfig.DEFAULT_PORT,
    val spkiPin: String,
    val turnSecret: String,
    val sni: String = "",
) {
    /** Converts this preset into the SDK-facing proxy config (null if incomplete). */
    fun toProxyConfig(): ProxyConfig? = ProxyConfig.fromInputs(
        enabled = true,
        host = host,
        port = port,
        spkiPinBase64 = spkiPin,
        turnSecret = turnSecret,
        sni = sni,
    )
}
