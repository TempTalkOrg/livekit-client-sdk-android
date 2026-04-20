/*
 * Copyright 2023-2026 LiveKit, Inc.
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

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Parcelable
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.TempTalkOrg.audio_pipeline.AudioModule
import com.github.TempTalkOrg.audio_pipeline.AudioPipelineProcessor
import com.github.TempTalkOrg.audio_pipeline.DeepFilterConfig
import com.github.TempTalkOrg.audio_pipeline.SoundTouchConfig
import com.xwray.groupie.GroupieAdapter
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.sample.common.R
import io.livekit.android.sample.databinding.CallActivityBinding
import io.livekit.android.sample.dialog.showAudioProcessorSwitchDialog
import io.livekit.android.sample.dialog.showDebugMenuDialog
import io.livekit.android.sample.dialog.showSelectAudioDeviceDialog
import io.livekit.android.sample.model.StressTest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

class CallActivity : AppCompatActivity() {
    private val denoiser: AudioPipelineProcessor by lazy { AudioPipelineProcessor(context = applicationContext, debugLog = true) }
    private var currentDfConfig = DeepFilterConfig()
    private var vcEnabled = false
    private var selectedPreset: String? = null

    private val viewModel: CallViewModel by viewModelByFactory {
        val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
            ?: throw NullPointerException("args is null!")

        CallViewModel(
            url = args.url,
            token = args.token,
            e2ee = args.e2eeOn,
            e2eeKey = args.e2eeKey,
            quic = args.quicOn,
            quicDeviceType = args.quicDeviceType,
            quicCidTag = args.quicCidTag,
            serverHost = args.serverHost,
            caCertPem = args.caCertPem,
            stressTest = args.stressTest,
            application = application,
            audioProcessorOptions = AudioProcessorOptions(
                capturePostProcessor = denoiser
            )
        )
    }
    private lateinit var binding: CallActivityBinding
    private val screenCaptureIntentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val resultCode = result.resultCode
            val data = result.data
            if (resultCode != RESULT_OK || data == null) {
                return@registerForActivityResult
            }
            viewModel.startScreenCapture(data)
        }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = CallActivityBinding.inflate(layoutInflater)

        setContentView(binding.root)

        // Audience row setup
        val audienceAdapter = GroupieAdapter()
        binding.audienceRow.apply {
            layoutManager = LinearLayoutManager(this@CallActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = audienceAdapter
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.participants
                    .collect { participants ->
                        val items = participants.map { participant -> ParticipantItem(viewModel.room, participant) }
                        audienceAdapter.update(items)
                    }
            }
        }

        // speaker view setup
        val speakerAdapter = GroupieAdapter()
        binding.speakerView.apply {
            layoutManager = LinearLayoutManager(this@CallActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = speakerAdapter
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.primarySpeaker.collectLatest { speaker ->
                    val items = listOfNotNull(speaker)
                        .map { participant -> ParticipantItem(viewModel.room, participant, speakerView = true) }
                    speakerAdapter.update(items)
                }
            }
        }

        // Controls setup
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.cameraEnabled.collect { enabled ->
                    binding.camera.setOnClickListener { viewModel.setCameraEnabled(!enabled) }
                    binding.camera.setImageResource(
                        if (enabled) {
                            R.drawable.outline_videocam_24
                        } else {
                            R.drawable.outline_videocam_off_24
                        },
                    )
                    binding.flipCamera.isEnabled = enabled
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.micEnabled.collect { enabled ->
                    binding.mic.setOnClickListener { viewModel.setMicEnabled(!enabled) }
                    binding.mic.setImageResource(
                        if (enabled) {
                            R.drawable.outline_mic_24
                        } else {
                            R.drawable.outline_mic_off_24
                        },
                    )
                }
            }
        }

        binding.flipCamera.setOnClickListener { viewModel.flipCamera() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.screenshareEnabled.collect { enabled ->
                    binding.screenShare.setOnClickListener {
                        if (enabled) {
                            viewModel.stopScreenCapture()
                        } else {
                            requestMediaProjection()
                        }
                    }
                    binding.screenShare.setImageResource(
                        if (enabled) {
                            R.drawable.baseline_cast_connected_24
                        } else {
                            R.drawable.baseline_cast_24
                        },
                    )
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.connectionStatus.collect { status ->
                    binding.connectionStatus.text = status
                }
            }
        }

        binding.message.setOnClickListener {
            val editText = EditText(this)
            AlertDialog.Builder(this)
                .setTitle("Send Message")
                .setView(editText)
                .setPositiveButton("Send") { dialog, _ ->
                    viewModel.sendData(editText.text?.toString() ?: "")
                }
                .setNegativeButton("Cancel") { _, _ -> }
                .create()
                .show()
        }

        viewModel.enhancedNsEnabled.observe(this) { enabled ->
            binding.enhancedNs.visibility = if (enabled) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        binding.enhancedNs.setOnClickListener {
            showAudioProcessorSwitchDialog(viewModel)
        }

        binding.exit.setOnClickListener { finish() }

        // Controls row 2
        binding.noiseCtl.setOnClickListener {
            if (denoiser.isEnabled()) {
                denoiser.setEnabled(false)
                binding.noiseCtl.setText(io.livekit.android.sample.R.string.denoise_close)
            } else {
                denoiser.setEnabled(true)
                binding.noiseCtl.setText(io.livekit.android.sample.R.string.denoise_open)
            }
        }

        binding.modelSwitch.setOnClickListener {
            val current = denoiser.getActiveModule()
            val next = when (current) {
                AudioModule.RNNOISE -> AudioModule.DEEP_FILTER_NET
                AudioModule.DEEP_FILTER_NET -> AudioModule.RNNOISE
            }
            denoiser.setModule(next)
            binding.modelSwitch.text = when (next) {
                AudioModule.RNNOISE -> getString(io.livekit.android.sample.R.string.ns_rnnoise)
                AudioModule.DEEP_FILTER_NET -> getString(io.livekit.android.sample.R.string.ns_deepfilter)
            }
            updateDfConfigPanel()
            Toast.makeText(this, "Switched to ${next.id}", Toast.LENGTH_SHORT).show()
        }

        binding.attenLimSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.attenLimValue.text = progress.toString()
                if (fromUser) {
                    currentDfConfig = currentDfConfig.copy(attenLimDb = progress.toFloat())
                    denoiser.updateDeepFilterConfig(currentDfConfig)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.postFilterSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val beta = progress / 100f
                binding.postFilterValue.text = String.format("%.2f", beta)
                if (fromUser) {
                    currentDfConfig = currentDfConfig.copy(postFilterBeta = beta)
                    denoiser.updateDeepFilterConfig(currentDfConfig)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        updateDfConfigPanel()

        // Voice changer controls
        binding.vcCtl.setOnClickListener {
            vcEnabled = !vcEnabled
            if (vcEnabled) {
                binding.vcCtl.setText(io.livekit.android.sample.R.string.vc_close)
                binding.voiceChangerPanel.visibility = android.view.View.VISIBLE
                val preset = selectedPreset ?: "original"
                denoiser.setSoundTouchPreset(preset)
            } else {
                binding.vcCtl.setText(io.livekit.android.sample.R.string.vc_open)
                binding.voiceChangerPanel.visibility = android.view.View.GONE
                denoiser.setSoundTouchConfig(SoundTouchConfig(enabled = false))
            }
        }

        val presetButtons = listOf(
            binding.presetLoli to "loli",
            binding.presetGoddess to "goddess",
            binding.presetOriginal to "original",
            binding.presetUncle to "uncle",
            binding.presetMonster to "monster",
        )
        for ((button, preset) in presetButtons) {
            button.setOnClickListener {
                selectedPreset = preset
                denoiser.setSoundTouchPreset(preset)
                updatePresetSelection(selectedPreset)
                // sync slider to preset semitones
                val semitones = SoundTouchConfig.PRESETS[preset]?.pitchSemiTones ?: 0f
                binding.pitchSlider.progress = (semitones + 12).toInt()
                binding.pitchValue.text = semitones.toInt().toString()
            }
        }

        binding.pitchSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val semitones = progress - 12
                binding.pitchValue.text = semitones.toString()
                if (fromUser) {
                    selectedPreset = null
                    updatePresetSelection(null)
                    denoiser.setSoundTouchConfig(SoundTouchConfig(enabled = true, pitchSemiTones = semitones.toFloat()))
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.audioSelect.setOnClickListener {
            showSelectAudioDeviceDialog(viewModel)
        }
        lifecycleScope.launchWhenCreated {
            viewModel.permissionAllowed.collect { allowed ->
                val resource = if (allowed) R.drawable.account_cancel_outline else R.drawable.account_cancel
                binding.permissions.setImageResource(resource)
            }
        }
        binding.permissions.setOnClickListener {
            viewModel.toggleSubscriptionPermissions()
        }

        binding.debugMenu.setOnClickListener {
            showDebugMenuDialog(viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launchWhenResumed {
            viewModel.error.collect {
                if (it != null) {
                    Toast.makeText(this@CallActivity, "Error: $it", Toast.LENGTH_LONG).show()
                    viewModel.dismissError()
                }
            }
        }

        lifecycleScope.launchWhenResumed {
            viewModel.dataReceived.collect {
                Toast.makeText(this@CallActivity, "Data received: $it", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updatePresetSelection(active: String?) {
        val presetButtons = listOf(
            binding.presetLoli to "loli",
            binding.presetGoddess to "goddess",
            binding.presetOriginal to "original",
            binding.presetUncle to "uncle",
            binding.presetMonster to "monster",
        )
        for ((button, preset) in presetButtons) {
            button.alpha = if (preset == active) 1.0f else 0.45f
        }
    }

    private fun updateDfConfigPanel() {
        val isDeepFilter = denoiser.getActiveModule() == AudioModule.DEEP_FILTER_NET
        binding.dfConfigPanel.visibility = if (isDeepFilter) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureIntentLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        binding.audienceRow.adapter = null
        binding.speakerView.adapter = null
        super.onDestroy()

        denoiser.release()
    }

    companion object {
        const val KEY_ARGS = "args"
    }

    @Parcelize
    data class BundleArgs(
        val url: String,
        val token: String,
        val e2eeKey: String,
        val e2eeOn: Boolean,
        val quicOn: Boolean,
        val quicDeviceType: Int,
        val quicCidTag: String,
        val serverHost: String = "",
        val caCertPem: String = "",
        val stressTest: StressTest,
    ) : Parcelable
}
