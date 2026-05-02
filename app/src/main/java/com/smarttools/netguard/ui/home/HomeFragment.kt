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
import com.smarttools.netguard.model.TrafficStatsMode
import com.smarttools.netguard.util.GeoLookup
import com.smarttools.netguard.util.TrafficFormatter
import com.smarttools.netguard.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var pulseAnimator: ObjectAnimator? = null
    private var timerRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as App
        val settings = app.loadSettings()

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

        // Connection map setup
        if (settings.showConnectionMap) {
            binding.connectionMap.visibility = View.VISIBLE
            binding.connectionMap.setMapImage(R.drawable.world_map)
            binding.connectionMap.setLocations(GeoLookup.getUserLocation(), null)
            // Fetch precise user location via IP in background — only when the
            // tunnel is up. ipwho.is otherwise sees the user's real IP, which
            // is exactly the leak the rest of the app is trying to prevent.
            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val state = com.smarttools.netguard.service.TunnelVpnService.connectionState.value
                if (state !is com.smarttools.netguard.model.ConnectionState.Connected) return@launch
                GeoLookup.fetchUserLocation()?.let { loc ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (_binding != null) {
                            binding.connectionMap.setLocations(loc, viewModel.selectedProfile.value?.let { GeoLookup.fromProfileName(it.name) })
                        }
                    }
                }
            }
        }

        // Speed test setup
        if (settings.showSpeedTest) {
            binding.btnSpeedTest.setOnClickListener {
                viewModel.runSpeedTest()
            }
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
                            cancelTimer()
                            binding.tvTimer.text = "00:00"
                        }
                        // Connection map
                        if (settings.showConnectionMap) {
                            when (state) {
                                is ConnectionState.Connected -> {
                                    val profile = viewModel.selectedProfile.value
                                    val serverLoc = profile?.let { GeoLookup.fromProfileName(it.name) }
                                    if (serverLoc != null) {
                                        binding.connectionMap.setLocations(GeoLookup.getUserLocation(), serverLoc)
                                        binding.connectionMap.setServerLabel(profile?.name)
                                        binding.connectionMap.setConnected(true)
                                    } else if (profile != null) {
                                        // IP fallback in background
                                        binding.connectionMap.setLocations(GeoLookup.getUserLocation(), null)
                                        binding.connectionMap.setConnected(false)
                                        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            val loc = GeoLookup.fromIp(profile.address)
                                            if (_binding != null) {
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    if (loc != null) {
                                                        binding.connectionMap.setLocations(GeoLookup.getUserLocation(), loc)
                                                        binding.connectionMap.setServerLabel(profile.name)
                                                        binding.connectionMap.setConnected(true)
                                                    } else {
                                                        binding.connectionMap.setServerLabel(getString(R.string.location_unknown))
                                                        binding.connectionMap.setConnected(false)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                else -> binding.connectionMap.setConnected(false)
                            }
                        }
                        // Speed test visibility
                        if (settings.showSpeedTest) {
                            binding.layoutSpeedTest.visibility =
                                if (state is ConnectionState.Connected) View.VISIBLE else View.GONE
                            if (state !is ConnectionState.Connected) {
                                binding.tvSpeedResult.visibility = View.GONE
                            }
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
                // Speed test collectors
                if (settings.showSpeedTest) {
                    launch {
                        viewModel.speedTesting.collect { testing ->
                            binding.btnSpeedTest.isEnabled = !testing
                            binding.progressSpeedTest.visibility = if (testing) View.VISIBLE else View.GONE
                        }
                    }
                    launch {
                        viewModel.speedResult.collect { result ->
                            if (result != null) {
                                binding.tvSpeedResult.visibility = View.VISIBLE
                                val dlText = if (result.downloadMbps < 0) "—" else String.format("%.1f", result.downloadMbps)
                                val ulText = if (result.uploadMbps < 0) "—" else String.format("%.1f", result.uploadMbps)
                                binding.tvSpeedResult.text = "\u2193 $dlText Mbps  \u2191 $ulText Mbps  Ping ${result.pingMs}ms"
                            }
                        }
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
        val app = requireActivity().application as App
        val statsRepo = app.statsRepository
        val mode = app.loadSettings().trafficStatsMode

        when (mode) {
            TrafficStatsMode.CHART -> {
                binding.trafficChart.visibility = View.VISIBLE
                binding.layoutSessionStats.visibility = View.VISIBLE
                binding.trafficChart.setData(statsRepo.getDailyHistory(7))
            }
            TrafficStatsMode.SIMPLE -> {
                binding.trafficChart.visibility = View.GONE
                binding.layoutSessionStats.visibility = View.VISIBLE
            }
            TrafficStatsMode.HIDDEN -> {
                binding.trafficChart.visibility = View.GONE
                binding.layoutSessionStats.visibility = View.GONE
            }
        }

        if (mode != TrafficStatsMode.HIDDEN) {
            val stats = statsRepo.getStats()
            binding.tvStatsToday.text = TrafficFormatter.formatBytes(stats.todayRx + stats.todayTx)
            binding.tvStatsWeek.text = TrafficFormatter.formatBytes(stats.weekRx + stats.weekTx)
            binding.tvStatsTotal.text = TrafficFormatter.formatBytes(stats.totalRx + stats.totalTx)
        }
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

    private fun cancelTimer() {
        timerRunnable?.let { _binding?.tvTimer?.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun updateTimer(startMs: Long) {
        cancelTimer()
        val elapsed = System.currentTimeMillis() - startMs
        binding.tvTimer.text = TrafficFormatter.formatDuration(elapsed)
        val runnable = Runnable {
            if (_binding != null && viewModel.connectionState.value is ConnectionState.Connected) {
                updateTimer(startMs)
            }
        }
        timerRunnable = runnable
        binding.tvTimer.postDelayed(runnable, 1000)
    }

    override fun onDestroyView() {
        cancelTimer()
        stopPulse()
        _binding = null
        super.onDestroyView()
    }
}
