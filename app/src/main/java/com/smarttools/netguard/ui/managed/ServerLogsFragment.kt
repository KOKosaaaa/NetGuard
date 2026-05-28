package com.smarttools.netguard.ui.managed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smarttools.netguard.databinding.FragmentServerLogsBinding
import kotlinx.coroutines.launch

/**
 * Per-service journalctl tail. Mirror of the agent's
 * /v1/services/{name}/logs endpoint with a service picker and a
 * Refresh button — no live-stream yet, just on-demand pulls. Auto-tail
 * lands in a follow-up once the WebSocket scaffolding is in place.
 */
class ServerLogsFragment : Fragment() {

    private var _b: FragmentServerLogsBinding? = null
    private val b get() = _b!!
    private val vm: ManagedServerDetailViewModel by activityViewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentServerLogsBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val services = listOf("xray", "sing-box", "headless-telemost-creator")
        b.spService.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            services,
        )
        b.btnRefresh.setOnClickListener {
            vm.refreshLogs(b.spService.selectedItem as String)
        }
        // Initial pull for xray, which is what most users care about.
        vm.refreshLogs("xray")

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.logs.collect { text -> b.tvLog.text = text }
            }
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
