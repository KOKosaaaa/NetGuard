package com.smarttools.netguard.ui.profiles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentProfileEditBinding
import com.smarttools.netguard.model.*
import com.smarttools.netguard.viewmodel.ProfileEditViewModel
import kotlinx.coroutines.launch

class ProfileEditFragment : Fragment() {

    private var _binding: FragmentProfileEditBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileEditViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val profileId = arguments?.getLong("profile_id", 0L) ?: 0L
        if (profileId > 0) {
            viewModel.loadProfile(profileId)
        }

        setupSpinners()
        setupButtons()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.profile.collect { p ->
                        bindProfile(p)
                    }
                }
                launch {
                    viewModel.saved.collect { saved ->
                        if (saved) findNavController().popBackStack()
                    }
                }
                launch {
                    viewModel.testResult.collect { result ->
                        result?.let {
                            binding.tvTestResult.text = it
                            binding.tvTestResult.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun setupSpinners() {
        val protocols = Protocol.entries.map { it.value }
        binding.spProtocol.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, protocols)

        val networks = TransportType.entries.map { it.value }
        binding.spNetwork.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, networks)

        val securities = SecurityType.entries.map { it.value }
        binding.spSecurity.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, securities)

        val fingerprints = listOf("chrome", "firefox", "safari", "ios", "android", "edge", "qq", "random", "randomized")
        binding.spFingerprint.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, fingerprints)
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            collectProfile()
            viewModel.save()
        }

        binding.btnDelete.setOnClickListener {
            viewModel.delete()
        }

        binding.btnTest.setOnClickListener {
            collectProfile()
            viewModel.testConnection()
        }

        binding.btnShare.setOnClickListener {
            collectProfile()
            val uri = viewModel.getShareUri()
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("profile", uri))
            Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
            // Auto-clear clipboard after 30 seconds for security
            binding.root.postDelayed({
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        clipboard.clearPrimaryClip()
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                } catch (_: Exception) {}
            }, 30_000)
        }
    }

    private fun bindProfile(p: ServerProfile) {
        binding.etName.setText(p.name)
        binding.etAddress.setText(p.address)
        binding.etPort.setText(if (p.port > 0) p.port.toString() else "")
        binding.etUuid.setText(p.uuid)
        binding.etPassword.setText(p.password)
        binding.etEncryption.setText(p.encryption)
        binding.etFlow.setText(p.flow)
        binding.etSni.setText(p.sni)
        binding.etAlpn.setText(p.alpn)
        binding.etHost.setText(p.host)
        binding.etPath.setText(p.path)
        binding.etServiceName.setText(p.serviceName)
        binding.etPublicKey.setText(p.publicKey)
        binding.etShortId.setText(p.shortId)
        binding.etSpiderX.setText(p.spiderX)
        binding.cbAllowInsecure.isChecked = p.allowInsecure

        binding.spProtocol.setSelection(Protocol.entries.indexOf(p.protocol).coerceAtLeast(0))
        binding.spNetwork.setSelection(TransportType.entries.indexOf(p.network).coerceAtLeast(0))
        binding.spSecurity.setSelection(SecurityType.entries.indexOf(p.security).coerceAtLeast(0))

        val fpIdx = (binding.spFingerprint.adapter as ArrayAdapter<String>).let { adapter ->
            (0 until adapter.count).firstOrNull { adapter.getItem(it) == p.fingerprint } ?: 0
        }
        binding.spFingerprint.setSelection(fpIdx)

        binding.btnDelete.visibility = if (p.id > 0) View.VISIBLE else View.GONE
    }

    private fun collectProfile() {
        viewModel.updateProfile { p ->
            p.copy(
                name = binding.etName.text.toString().trim(),
                address = binding.etAddress.text.toString().trim(),
                port = binding.etPort.text.toString().toIntOrNull() ?: 443,
                uuid = binding.etUuid.text.toString().trim(),
                password = binding.etPassword.text.toString().trim(),
                encryption = binding.etEncryption.text.toString().trim(),
                flow = binding.etFlow.text.toString().trim(),
                protocol = Protocol.entries.getOrElse(binding.spProtocol.selectedItemPosition) { Protocol.VLESS },
                network = TransportType.entries.getOrElse(binding.spNetwork.selectedItemPosition) { TransportType.TCP },
                security = SecurityType.entries.getOrElse(binding.spSecurity.selectedItemPosition) { SecurityType.NONE },
                fingerprint = binding.spFingerprint.selectedItem?.toString() ?: "chrome",
                sni = binding.etSni.text.toString().trim(),
                alpn = binding.etAlpn.text.toString().trim(),
                host = binding.etHost.text.toString().trim(),
                path = binding.etPath.text.toString().trim(),
                serviceName = binding.etServiceName.text.toString().trim(),
                publicKey = binding.etPublicKey.text.toString().trim(),
                shortId = binding.etShortId.text.toString().trim(),
                spiderX = binding.etSpiderX.text.toString().trim(),
                allowInsecure = binding.cbAllowInsecure.isChecked
            )
        }
    }

    override fun onDestroyView() {
        _binding?.root?.handler?.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }
}
