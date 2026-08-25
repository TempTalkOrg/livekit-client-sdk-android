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

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MainViewModelQuicConnectTimeoutTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(application).edit().clear().commit()
    }

    @After
    fun tearDown() {
        PreferenceManager.getDefaultSharedPreferences(application).edit().clear().commit()
    }

    @Test
    fun `timeout preference defaults to 7000`() {
        assertEquals(DEFAULT_QUIC_CONNECT_TIMEOUT_MS, MainViewModel(application).getQuicConnectTimeoutMs())
    }

    @Test
    fun `timeout preference saves and reset restores default`() {
        val viewModel = MainViewModel(application)

        viewModel.setQuicConnectTimeoutMs(9200)
        assertEquals(9200, MainViewModel(application).getQuicConnectTimeoutMs())

        viewModel.reset()
        assertEquals(DEFAULT_QUIC_CONNECT_TIMEOUT_MS, viewModel.getQuicConnectTimeoutMs())
    }
}
