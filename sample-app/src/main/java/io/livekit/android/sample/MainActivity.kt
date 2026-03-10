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

        // Set up the URL dropdown
        val urls = arrayOf(
            MainViewModel.URL,
            // "https://192.168.1.3:7880/"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, urls)
        binding.urlDropdown.setAdapter(adapter)

        val urlString = viewModel.getSavedUrl()
        val tokenString = viewModel.getSavedToken()
        val e2EEOn = viewModel.getE2EEOptionsOn()
        val e2EEKey = viewModel.getSavedE2EEKey()

        binding.run {
            urlDropdown.setText(urlString, false)
            token.editText?.text = SpannableStringBuilder(tokenString)
            e2eeEnabled.isChecked = e2EEOn
            e2eeKey.editText?.text = SpannableStringBuilder(e2EEKey)
            connectButton.setOnClickListener {
                val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                    putExtra(
                        CallActivity.KEY_ARGS,
                        CallActivity.BundleArgs(
                            url = urlDropdown.text.toString(),
                            token = token.editText?.text.toString(),
                            e2eeOn = e2eeEnabled.isChecked,
                            e2eeKey = e2eeKey.editText?.text.toString(),
                            quicOn = quicEnabled.isChecked,
                            stressTest = StressTest.None,
                        ),
                    )
                }

                startActivity(intent)
            }

            saveButton.setOnClickListener {
                viewModel.setSavedUrl(urlDropdown.text?.toString() ?: "")
                viewModel.setSavedToken(token.editText?.text?.toString() ?: "")
                viewModel.setSavedE2EEOn(e2eeEnabled.isChecked)
                viewModel.setSavedE2EEKey(e2eeKey.editText?.text?.toString() ?: "")

                Toast.makeText(
                    this@MainActivity,
                    "Values saved.",
                    Toast.LENGTH_SHORT,
                ).show()
            }

            resetButton.setOnClickListener {
                viewModel.reset()
                urlDropdown.setText(MainViewModel.URL, false)
                token.editText?.text = SpannableStringBuilder(MainViewModel.TOKEN)
                e2eeEnabled.isChecked = false
                e2eeKey.editText?.text = SpannableStringBuilder("")

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
