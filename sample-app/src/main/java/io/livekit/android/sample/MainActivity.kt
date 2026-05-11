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

package io.livekit.android.sample

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import io.livekit.android.sample.databinding.MainActivityBinding
import io.livekit.android.sample.model.StressTest
import io.livekit.android.sample.util.requestNeededPermissions

class MainActivity : AppCompatActivity() {

    private val viewModel by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = MainActivityBinding.inflate(layoutInflater)

        val presets = viewModel.presets
        val presetLabels = presets.map { it.label }.toTypedArray()
        val presetAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, presetLabels)
        binding.presetDropdown.setAdapter(presetAdapter)

        val selectedPreset = viewModel.getSelectedPreset()
        binding.presetDropdown.setText(selectedPreset.label, false)

        val e2EEOn = viewModel.getE2EEOptionsOn()
        val e2EEKey = viewModel.getSavedE2EEKey()
        val savedQuicDeviceType = viewModel.getQuicDeviceType()
        val savedQuicCidTag = viewModel.getQuicCidTag()

        binding.run {
            e2eeEnabled.isChecked = e2EEOn
            e2eeKey.editText?.text = SpannableStringBuilder(e2EEKey)
            quicEnabled.isChecked = selectedPreset.useQuicSignal
            quicDeviceType.editText?.text = SpannableStringBuilder(savedQuicDeviceType.toString())
            quicCidTag.editText?.text = SpannableStringBuilder(savedQuicCidTag)

            presetDropdown.setOnItemClickListener { _, _, position, _ ->
                val preset = presets[position]
                quicEnabled.isChecked = preset.useQuicSignal
            }

            connectButton.setOnClickListener {
                val selectedLabel = presetDropdown.text.toString()
                val preset = presets.find { it.label == selectedLabel } ?: presets.first()
                val quicDeviceTypeInt =
                    quicDeviceType.editText?.text?.toString()?.toIntOrNull()
                        ?: MainViewModel.DEFAULT_QUIC_DEVICE_TYPE
                val quicCidTagStr = quicCidTag.editText?.text?.toString().orEmpty()
                val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                    putExtra(
                        CallActivity.KEY_ARGS,
                        CallActivity.BundleArgs(
                            url = preset.url,
                            token = preset.token,
                            e2eeOn = e2eeEnabled.isChecked,
                            e2eeKey = e2eeKey.editText?.text.toString(),
                            quicOn = quicEnabled.isChecked,
                            quicDeviceType = quicDeviceTypeInt,
                            quicCidTag = quicCidTagStr,
                            serverHost = preset.serverHost,
                            caCertPem = preset.caCertPem,
                            stressTest = StressTest.None,
                        ),
                    )
                }

                startActivity(intent)
            }

            saveButton.setOnClickListener {
                val selectedLabel = presetDropdown.text.toString()
                val preset = presets.find { it.label == selectedLabel }
                if (preset != null) {
                    viewModel.setSavedPresetId(preset.id)
                }
                viewModel.setSavedE2EEOn(e2eeEnabled.isChecked)
                viewModel.setSavedE2EEKey(e2eeKey.editText?.text?.toString() ?: "")
                viewModel.setQuicDeviceType(
                    quicDeviceType.editText?.text?.toString()?.toIntOrNull()
                        ?: MainViewModel.DEFAULT_QUIC_DEVICE_TYPE,
                )
                viewModel.setQuicCidTag(quicCidTag.editText?.text?.toString().orEmpty())

                Toast.makeText(
                    this@MainActivity,
                    "Values saved.",
                    Toast.LENGTH_SHORT,
                ).show()
            }

            resetButton.setOnClickListener {
                viewModel.reset()
                val defaultPreset = presets.first()
                presetDropdown.setText(defaultPreset.label, false)
                quicEnabled.isChecked = defaultPreset.useQuicSignal
                e2eeEnabled.isChecked = false
                e2eeKey.editText?.text = SpannableStringBuilder("")
                quicDeviceType.editText?.text =
                    SpannableStringBuilder(MainViewModel.DEFAULT_QUIC_DEVICE_TYPE.toString())
                quicCidTag.editText?.text = SpannableStringBuilder(MainViewModel.DEFAULT_QUIC_CID_TAG)

                Toast.makeText(
                    this@MainActivity,
                    "Values reset.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        setContentView(binding.root)

        requestNeededPermissions()
    }
}
