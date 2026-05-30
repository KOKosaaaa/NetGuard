package com.smarttools.netguard.ui.managed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentAddServerBinding
import kotlinx.coroutines.launch

/**
 * SSH-driven bootstrap wizard. Wraps [AddServerViewModel] into a
 * 3-state UI so the user can see what's happening during the 30-60
 * seconds the bootstrap takes.
 *
 * Back-press while a bootstrap is in flight is intercepted with a
 * confirm dialog — pressing back at exactly the wrong moment would
 * orphan the agent in a half-installed state on the server.
 */
class AddServerFragment : Fragment() {

    private var _binding: FragmentAddServerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AddServerViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAddServerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            if (viewModel.state.value is AddServerViewModel.State.Progress) {
                confirmCancel()
            } else {
                findNavController().navigateUp()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.state.value is AddServerViewModel.State.Progress) {
                        confirmCancel()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        binding.btnDeploy.setOnClickListener { tryDeploy() }
        // Pair-via-URL block hidden via XML comment 2026-05-29 — bring
        // the listener (and tryPairUrl below) back when the install
        // command actually exists for end users.
        // binding.btnPairUrl.setOnClickListener { tryPairUrl() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun tryDeploy() {
        val host = binding.etHost.text?.toString()?.trim().orEmpty()
        if (host.isEmpty()) {
            binding.etHost.error = getString(R.string.add_server_host_hint)
            return
        }
        val port = binding.etPort.text?.toString()?.toIntOrNull() ?: 22
        val user = binding.etUser.text?.toString()?.trim().orEmpty()
        val pass = binding.etPassword.text?.toString().orEmpty()
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        // Drop the soft keyboard so the progress bar and stage label
        // aren't hidden behind the IME on smaller phones.
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.btnDeploy.windowToken, 0)
        viewModel.deploy(
            name = name,
            host = host,
            port = port,
            sshUser = user,
            sshPassword = pass,
        )
    }

    // Pair-via-URL flow hidden 2026-05-29 — keep the function (and the
    // strings, and ViewModel.pairByUrl) around for when the install
    // command flow ships, but nothing in the UI references it right now.
    /*
    private fun tryPairUrl() {
        val endpoint = binding.etEndpointUrl.text?.toString()?.trim().orEmpty()
        val token = binding.etPairToken.text?.toString()?.trim().orEmpty()
        if (endpoint.isEmpty()) {
            binding.etEndpointUrl.error = getString(R.string.pair_url_endpoint_empty)
            return
        }
        if (!endpoint.startsWith("https://", ignoreCase = true)) {
            binding.etEndpointUrl.error = getString(R.string.pair_url_endpoint_invalid)
            return
        }
        if (token.isEmpty()) {
            binding.etPairToken.error = getString(R.string.pair_url_token_empty)
            return
        }
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.btnPairUrl.windowToken, 0)
        viewModel.pairByUrl(name = name, endpointUrl = endpoint, pairToken = token)
    }
    */

    private fun render(state: AddServerViewModel.State) {
        binding.stateInput.visibility = if (state is AddServerViewModel.State.Input) View.VISIBLE else View.GONE
        binding.stateProgress.visibility = if (state is AddServerViewModel.State.Progress) View.VISIBLE else View.GONE
        binding.stateResult.visibility = if (state is AddServerViewModel.State.Success || state is AddServerViewModel.State.Failure) View.VISIBLE else View.GONE

        when (state) {
            is AddServerViewModel.State.Input -> Unit
            is AddServerViewModel.State.Progress -> {
                binding.pbStage.progress = state.stage.pct
                binding.tvStageLabel.text = stageLabel(state.stage)
                binding.tvStagePct.text = "${state.stage.pct}%"
            }
            is AddServerViewModel.State.Success -> {
                binding.tvResultTitle.text = getString(R.string.add_server_success)
                binding.tvResultTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.status_connected)
                )
                binding.tvResultBody.text = getString(
                    R.string.import_success_format,
                    1, state.server.name,
                )
                binding.btnPrimary.text = getString(R.string.continue_action)
                binding.btnPrimary.setOnClickListener { findNavController().navigateUp() }
                binding.btnSecondary.visibility = View.GONE
                binding.btnShowLog.setOnClickListener {
                    showLogDialog(state.transcript)
                }
            }
            is AddServerViewModel.State.Failure -> {
                binding.tvResultTitle.text = getString(R.string.add_server_failed)
                binding.tvResultTitle.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.status_error)
                )
                binding.tvResultBody.text = "${state.code}: ${state.message}"
                binding.btnPrimary.text = getString(R.string.retry)
                binding.btnPrimary.setOnClickListener { viewModel.resetToInput() }
                binding.btnSecondary.visibility = View.VISIBLE
                binding.btnSecondary.text = getString(android.R.string.cancel)
                binding.btnSecondary.setOnClickListener { findNavController().navigateUp() }
                binding.btnShowLog.setOnClickListener {
                    showLogDialog(state.transcript)
                }
            }
        }
    }

    private fun stageLabel(s: com.smarttools.netguard.agent.SshBootstrap.Stage): String {
        val id = resources.getIdentifier(s.labelKey, "string", requireContext().packageName)
        return if (id != 0) getString(id) else s.name
    }

    private fun confirmCancel() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_server)
            .setMessage("Deploy in progress. Cancel and back out?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                findNavController().navigateUp()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLogDialog(transcript: String) {
        val tv = TextView(requireContext()).apply {
            text = transcript.ifBlank { "(empty)" }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_server_show_log)
            .setView(android.widget.ScrollView(requireContext()).apply { addView(tv) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
