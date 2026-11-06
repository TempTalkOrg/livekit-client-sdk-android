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

package io.livekit.android.room.network

import android.net.ConnectivityManager.NetworkCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock

class NetworkCallbackManagerTest {

    @Test
    fun registersDefaultNetworkCallbackOnlyOnce() {
        val callback = mock<NetworkCallback>()
        val registry = RecordingNetworkCallbackRegistry()
        val manager = NetworkCallbackManagerImpl(callback, registry)

        manager.registerCallback()
        manager.registerCallback()

        assertEquals(1, registry.registerCount)
        assertSame(callback, registry.registeredCallback)
    }

    @Test
    fun unregistersRegisteredDefaultNetworkCallback() {
        val callback = mock<NetworkCallback>()
        val registry = RecordingNetworkCallbackRegistry()
        val manager = NetworkCallbackManagerImpl(callback, registry)

        manager.registerCallback()
        manager.unregisterCallback()

        assertEquals(1, registry.unregisterCount)
        assertSame(callback, registry.unregisteredCallback)
    }

    private class RecordingNetworkCallbackRegistry : NetworkCallbackRegistry {
        var registerCount = 0
        var unregisterCount = 0
        var registeredCallback: NetworkCallback? = null
        var unregisteredCallback: NetworkCallback? = null

        override fun registerDefaultNetworkCallback(networkCallback: NetworkCallback) {
            registerCount++
            registeredCallback = networkCallback
        }

        override fun unregisterNetworkCallback(networkCallback: NetworkCallback) {
            unregisterCount++
            unregisteredCallback = networkCallback
        }
    }
}
