package com.smarttools.netguard.ui.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smarttools.netguard.App
import com.smarttools.netguard.MainActivity
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentHomeBinding
import com.smarttools.netguard.model.ConnectionState
import com.smarttools.netguard.util.TrafficFormatter
import com.smarttools.netguard.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnConnect.setOnClickListener {
            val state = viewModel.connectionState.value
            if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
                (activity as? MainActivity)?.requestVpnPermissionAndConnect()
            } else {
                viewModel.disconnect()
            }
        }

        binding.btnAutoSelect.setOnClickListener {
            viewModel.autoSelectAndConnect()
        }

        updateSessionStats()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        updateUI(state)
                        if (state is ConnectionState.Connected) {
                            updateTimer(state.startTimeMs)
                        } else {
                            binding.tvTimer.text = "00:00"
                        }
                        if (state is ConnectionState.Disconnected) {
                            updateSessionStats()
                        }
                    }
                }
                launch {
                    viewModel.trafficStats.collect { stats ->
                        binding.tvDownSpeed.text = TrafficFormatter.formatSpeed(stats.rxSpeed)
                        binding.tvUpSpeed.text = TrafficFormatter.formatSpeed(stats.txSpeed)
                        binding.tvDownTotal.text = TrafficFormatter.formatBytes(stats.rxBytes)
                        binding.tvUpTotal.text = TrafficFormatter.formatBytes(stats.txBytes)
                    }
                }
                launch {
                    viewModel.selectedProfile.collect { profile ->
                        binding.tvProfileName.text = profile?.name ?: getString(R.string.no_profile_selected)
                    }
                }
                launch {
                    viewModel.autoSelecting.collect { selecting ->
                        binding.btnAutoSelect.isEnabled = !selecting
                        binding.progressAutoSelect.visibility = if (selecting) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.autoSelectMessage.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updateSessionStats()
        }
    }

    private fun updateSessionStats() {
        val stats = (requireActivity().application as App).statsRepository.getStats()
        binding.tvStatsToday.text = TrafficFormatter.formatBytes(stats.todayRx + stats.todayTx)
        binding.tvStatsWeek.text = TrafficFormatter.formatBytes(stats.weekRx + stats.weekTx)
        binding.tvStatsTotal.text = TrafficFormatter.formatBytes(stats.totalRx + stats.totalTx)
    }

    private fun updateUI(state: ConnectionState) {
        when (state) {
            is ConnectionState.Disconnected -> {
                binding.btnConnect.setBackgroundResource(R.drawable.bg_button_disconnected)
                binding.tvStatus.text = getString(R.string.status_disconnected)
                binding.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_disconnected))
                stopPulse()
            }
            is ConnectionState.Connecting -> {
                binding.btnConnect.setBackgroundResource(R.drawable.bg_button_connecting)
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connecting))
                stopPulse()
            }
            is ConnectionState.Connected -> {
                binding.btnConnect.setBackgroundResource(R.drawable.bg_button_connected)
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connected))
                startPulse()
            }
            is ConnectionState.Error -> {
                binding.btnConnect.setBackgroundResource(R.drawable.bg_button_disconnected)
                binding.tvStatus.text = state.message
                binding.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
                stopPulse()
            }
        }
    }

    private fun startPulse() {
        if (pulseAnimator == null) {
            pulseAnimator = ObjectAnimator.ofFloat(binding.btnConnect, "alpha", 1f, 0.7f).apply {
                duration = 1200
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.btnConnect.alpha = 1f
    }

    private fun updateTimer(startMs: Long) {
        val elapsed = System.currentTimeMillis() - startMs
        binding.tvTimer.text = TrafficFormatter.formatDuration(elapsed)
        binding.tvTimer.postDelayed({
            if (_binding != null && viewModel.connectionState.value is ConnectionState.Connected) {
                updateTimer(startMs)
            }
        }, 1000)
    }

    override fun onDestroyView() {
        // Remove all pending timer callbacks to prevent View leak
        _binding?.tvTimer?.handler?.removeCallbacksAndMessages(null)
        _binding?.root?.handler?.removeCallbacksAndMessages(null)
        stopPulse()
        _binding = null
        super.onDestroyView()
    }
}
