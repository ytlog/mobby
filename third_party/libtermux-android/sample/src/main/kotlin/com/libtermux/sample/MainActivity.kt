package com.libtermux.sample

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.libtermux.sample.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupObservers()
        setupListeners()

        // Auto-install on launch
        vm.install()
    }

    private fun setupObservers() {
        vm.uiState.onEach { state ->
            binding.tvStatus.text = state.status
            binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

            if (state.isLoading && state.installProgress > 0f) {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = (state.installProgress * 100).toInt()
            } else if (state.isLoading) {
                binding.progressBar.isIndeterminate = true
            }

            if (state.isReady) {
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            }
        }.launchIn(lifecycleScope)

        vm.events.onEach { event ->
            when (event) {
                is UiEvent.Output -> binding.terminalView.appendText(event.text, event.isError)
                is UiEvent.Toast  -> Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
            }
        }.launchIn(lifecycleScope)
    }

    private fun setupListeners() {
        binding.btnInstall.setOnClickListener { vm.install(forceReinstall = false) }
        binding.btnPython.setOnClickListener  { vm.runPythonDemo() }
        binding.btnSysInfo.setOnClickListener { vm.runSysInfo() }
        binding.btnClear.setOnClickListener   { binding.terminalView.clear() }

        binding.btnRun.setOnClickListener {
            val cmd = binding.etCommand.text?.toString()?.trim() ?: return@setOnClickListener
            if (cmd.isNotEmpty()) {
                vm.runCommand(cmd)
                binding.etCommand.text?.clear()
            }
        }

        binding.etCommand.setOnEditorActionListener { _, _, _ ->
            binding.btnRun.performClick()
            true
        }
    }
}
