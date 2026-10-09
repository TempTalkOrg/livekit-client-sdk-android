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

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import io.livekit.android.ConnectOptions

internal fun Context.findPhysicalNetworkHandle(): Long {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return 0
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return connectivityManager.allNetworks
        .filter { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
                ?: return@filter false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    )
        }
        .minByOrNull { network -> physicalNetworkPriority(connectivityManager, network) }
        ?.networkHandle
        ?: 0
}

private fun physicalNetworkPriority(
    connectivityManager: ConnectivityManager,
    network: Network,
): Int {
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return Int.MAX_VALUE
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
        else -> 3
    }
}

internal fun ConnectOptions.withPhysicalRouting(
    enabled: Boolean,
    networkHandle: Long,
): ConnectOptions {
    val shouldForcePhysical = useQuicSignal && enabled
    return copy(
        forcePhysical = shouldForcePhysical,
        physicalNetworkHandle = if (shouldForcePhysical) networkHandle else 0,
    )
}
