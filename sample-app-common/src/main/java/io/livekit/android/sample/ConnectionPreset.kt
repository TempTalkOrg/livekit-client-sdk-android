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

/**
 * A connection preset from `local-config.json` (same format as the JS/iOS demos).
 *
 * [env], [region], [provider], [via] and [tag] are displayed verbatim.
 */
@kotlinx.serialization.Serializable
data class ConnectionPreset(
    /** Only needs to be unique within its [env]. */
    val id: String,
    /** Replaces the URL host in [optionText]; tag/via/transport are still appended. */
    val label: String? = null,
    /** Environment, e.g. `Test` / `Prod`. */
    val env: String? = null,
    /** Node location, e.g. `SG` / `HK` / `US-CA`. */
    val region: String? = null,
    /** Hosting provider of the node, e.g. `AWS` / `Aliyun`. */
    val provider: String? = null,
    /** Relay ingress in front of the node, e.g. `Aliyun` for a mainland HTTPS/WSS relay to an overseas node. */
    val via: String? = null,
    /** Free-form test scenario, e.g. `self-cert` / `error-cert`. */
    val tag: String? = null,
    val url: String,
    val token: String,
    val useQuicSignal: Boolean = false,
    val serverHost: String = "",
    val caCertPem: String = "",
) {
    /** Unique across environments; used for selection and persistence. */
    val key: String
        get() = if (env.isNullOrEmpty()) id else "$env/$id"

    /** Dropdown group: env · region · provider. */
    val groupLabel: String
        get() = listOf(env?.takeIf { it.isNotEmpty() } ?: ENV_OTHER, region, provider)
            .filterNot { it.isNullOrEmpty() }
            .joinToString(SEPARATOR)

    /** Row text within its group: address · tag · via · transport (+ serverHost for QUIC). */
    val optionText: String
        get() {
            val urlHost = urlHost(url)
            val parts = mutableListOf(label?.trim()?.takeIf { it.isNotEmpty() } ?: urlHost)
            tag?.takeIf { it.isNotEmpty() }?.let(parts::add)
            via?.takeIf { it.isNotEmpty() }?.let { parts.add("via $it") }
            if (useQuicSignal) {
                val host = serverHost.trim()
                parts.add(if (host.isNotEmpty() && host != urlHost) "QUIC ($host)" else "QUIC")
            } else {
                parts.add("WS")
            }
            return parts.joinToString(SEPARATOR)
        }

    /** Full text for the collapsed field, where the group header is not visible. */
    val displayText: String
        get() = "$groupLabel$SEPARATOR$optionText"

    private companion object {
        const val ENV_OTHER = "Other"
        const val SEPARATOR = " · "

        /** Host exactly as written; [java.net.URI] would normalize it. */
        fun urlHost(url: String): String {
            val start = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return url
            val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).takeIf { it >= 0 } ?: url.length
            return url.substring(start, end)
        }
    }
}
