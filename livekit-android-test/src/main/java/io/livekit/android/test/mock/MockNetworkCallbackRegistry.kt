/*
 * Copyright 2024-2026 LiveKit, Inc.
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

import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import io.livekit.android.room.network.NetworkCallbackRegistry
import org.mockito.Mockito
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

class MockNetworkCallbackRegistry : NetworkCallbackRegistry {
    val networkCallbacks = mutableSetOf<NetworkCallback>()

    /**
     * Reported on registration, like the platform does for the default network.
     */
    val defaultNetwork: Network = Mockito.mock(Network::class.java)

    /**
     * When false, registration delivers nothing and the test drives the callbacks itself,
     * like the asynchronous delivery on a device.
     */
    var notifyOnRegistration = true

    override fun registerDefaultNetworkCallback(networkCallback: NetworkCallback) {
        networkCallbacks.add(networkCallback)
        if (notifyOnRegistration) {
            networkCallback.notifyAvailable(defaultNetwork)
        }
    }

    /**
     * Simulates [network] becoming the default network on all registered callbacks.
     */
    fun simulateNetworkAvailable(network: Network) {
        networkCallbacks.toList().forEach { it.notifyAvailable(network) }
    }

    // Since API 26 the platform always follows onAvailable with onCapabilitiesChanged.
    private fun NetworkCallback.notifyAvailable(network: Network) {
        onAvailable(network)
        onCapabilitiesChanged(network, createCapabilities())
    }

    fun createCapabilities(): NetworkCapabilities {
        return ShadowNetworkCapabilities.newInstance().apply {
            shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    override fun unregisterNetworkCallback(networkCallback: NetworkCallback) {
        networkCallbacks.remove(networkCallback)
    }
}
