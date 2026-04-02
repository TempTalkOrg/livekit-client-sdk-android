package io.livekit.android.sample

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import io.livekit.android.sample.common.BuildConfig

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)

    fun getSavedUrl() = preferences.getString(PREFERENCES_KEY_URL, URL) as String
    fun getSavedToken() = preferences.getString(PREFERENCES_KEY_TOKEN, TOKEN) as String
    fun getE2EEOptionsOn() = preferences.getBoolean(PREFERENCES_KEY_E2EE_ON, false)
    fun getSavedE2EEKey() = preferences.getString(PREFERENCES_KEY_E2EE_KEY, E2EE_KEY) as String
    fun getQuicSignalOn() = preferences.getBoolean(PREFERENCES_KEY_QUIC_SIGNAL_ON, false)
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

    fun setQuicSignalOn(enabled: Boolean) {
        preferences.edit {
            putBoolean(PREFERENCES_KEY_QUIC_SIGNAL_ON, enabled)
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
        private const val PREFERENCES_KEY_E2EE_ON = "enable_e2ee"
        private const val PREFERENCES_KEY_E2EE_KEY = "e2ee_key"
        private const val PREFERENCES_KEY_QUIC_SIGNAL_ON = "enable_quic_signal"
        private const val PREFERENCES_KEY_QUIC_DEVICE_TYPE = "quic_device_type"
        private const val PREFERENCES_KEY_QUIC_CID_TAG = "quic_cid_tag"

        const val URL = BuildConfig.DEFAULT_URL
        const val TOKEN = BuildConfig.DEFAULT_TOKEN
        const val E2EE_KEY = "12345678"
        val DEFAULT_QUIC_DEVICE_TYPE: Int = BuildConfig.DEFAULT_QUIC_DEVICE_TYPE
        val DEFAULT_QUIC_CID_TAG: String = BuildConfig.DEFAULT_QUIC_CID_TAG
    }
}
