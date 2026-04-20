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
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import io.livekit.android.sample.common.BuildConfig
import kotlinx.serialization.json.Json

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)

    val presets: List<ConnectionPreset> = parsePresets()

    fun getSavedPresetId(): String {
        val savedId = preferences.getString(PREFERENCES_KEY_PRESET_ID, null)
        return if (savedId != null && presets.any { it.id == savedId }) {
            savedId
        } else {
            presets.firstOrNull()?.id ?: ""
        }
    }

    fun setSavedPresetId(id: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_PRESET_ID, id)
        }
    }

    fun getPresetById(id: String): ConnectionPreset? = presets.find { it.id == id }

    fun getSelectedPreset(): ConnectionPreset {
        return getPresetById(getSavedPresetId()) ?: presets.first()
    }

    fun getSavedUrl() = preferences.getString(PREFERENCES_KEY_URL, URL) as String
    fun getSavedToken() = preferences.getString(PREFERENCES_KEY_TOKEN, TOKEN) as String
    fun getE2EEOptionsOn() = preferences.getBoolean(PREFERENCES_KEY_E2EE_ON, false)
    fun getSavedE2EEKey() = preferences.getString(PREFERENCES_KEY_E2EE_KEY, E2EE_KEY) as String
    fun getQuicDeviceType() = preferences.getInt(PREFERENCES_KEY_QUIC_DEVICE_TYPE, DEFAULT_QUIC_DEVICE_TYPE)
    fun getQuicCidTag() = preferences.getString(PREFERENCES_KEY_QUIC_CID_TAG, DEFAULT_QUIC_CID_TAG) ?: DEFAULT_QUIC_CID_TAG

    fun setSavedUrl(url: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_URL, url)
        }
    }

    fun setSavedToken(token: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_TOKEN, token)
        }
    }

    fun setSavedE2EEOn(yesno: Boolean) {
        preferences.edit {
            putBoolean(PREFERENCES_KEY_E2EE_ON, yesno)
        }
    }

    fun setSavedE2EEKey(key: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_E2EE_KEY, key)
        }
    }

    fun setQuicDeviceType(value: Int) {
        preferences.edit {
            putInt(PREFERENCES_KEY_QUIC_DEVICE_TYPE, value)
        }
    }

    fun setQuicCidTag(value: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_QUIC_CID_TAG, value)
        }
    }

    fun reset() {
        preferences.edit { clear() }
    }

    companion object {
        private const val PREFERENCES_KEY_URL = "url"
        private const val PREFERENCES_KEY_TOKEN = "token"
        private const val PREFERENCES_KEY_PRESET_ID = "preset_id"
        private const val PREFERENCES_KEY_E2EE_ON = "enable_e2ee"
        private const val PREFERENCES_KEY_E2EE_KEY = "e2ee_key"
        private const val PREFERENCES_KEY_QUIC_DEVICE_TYPE = "quic_device_type"
        private const val PREFERENCES_KEY_QUIC_CID_TAG = "quic_cid_tag"

        const val URL = BuildConfig.DEFAULT_URL
        const val TOKEN = BuildConfig.DEFAULT_TOKEN
        const val E2EE_KEY = "12345678"
        val DEFAULT_QUIC_DEVICE_TYPE: Int = BuildConfig.DEFAULT_QUIC_DEVICE_TYPE
        val DEFAULT_QUIC_CID_TAG: String = BuildConfig.DEFAULT_QUIC_CID_TAG

        private val json = Json { ignoreUnknownKeys = true }

        private fun parsePresets(): List<ConnectionPreset> {
            return try {
                json.decodeFromString<List<ConnectionPreset>>(BuildConfig.SAMPLE_PRESETS_JSON)
            } catch (_: Exception) {
                listOf(
                    ConnectionPreset(
                        id = "default",
                        label = "Default",
                        url = URL,
                        token = TOKEN,
                    ),
                )
            }
        }
    }
}
