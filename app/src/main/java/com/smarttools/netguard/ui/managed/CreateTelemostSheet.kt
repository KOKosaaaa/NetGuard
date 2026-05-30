package com.smarttools.netguard.ui.managed

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.BottomSheetCreateTelemostBinding
import kotlinx.coroutines.launch

/**
 * "New Telemost" bottom sheet. Reached from the per-server detail
 * screen (or eventually from the universal CreateProfileSheet's
 * Telemost card). Walks the user through:
 *   1. Sign in with Yandex (launches [YandexLoginActivity], receives
 *      cookies back through ActivityResult).
 *   2. Pick a stream count 1..12 with dynamic pros/cons.
 *   3. Tap "Set up Telemost" — fires /v1/telemost/deploy and polls
 *      until done; on success drops the resulting telemost:// profile
 *      into the regular Servers list.
 *
 * Server context comes from [ManagedServerDetailViewModel.status]
 * which we observe through the activity-scoped VM.
 */
class CreateTelemostSheet : BottomSheetDialogFragment() {

    private var _b: BottomSheetCreateTelemostBinding? = null
    private val b get() = _b!!
    private val srvVm: ManagedServerDetailViewModel by activityViewModels()
    private val vm: CreateTelemostViewModel by activityViewModels()

    private val yandexLogin = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(YandexLoginActivity.EXTRA_COOKIES_JSON)
            if (!json.isNullOrEmpty()) vm.setCookies(json)
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = BottomSheetCreateTelemostBinding.inflate(i, c, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Server binding — when the parent's status feed yields a row,
        // we tell the VM and refresh the capacity hint.
        srvVm.serverOrNull?.let { vm.bindServer(it) }

        b.btnConnectYandex.setOnClickListener {
            yandexLogin.launch(Intent(requireContext(), YandexLoginActivity::class.java))
        }

        b.sliderCount.addOnChangeListener { _, value, _ ->
            renderCount(value.toInt())
        }
        renderCount(b.sliderCount.value.toInt())

        b.btnCreate.setOnClickListener {
            vm.build(b.sliderCount.value.toInt())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.cookiesJson.collect { json ->
                    if (json.isNullOrEmpty()) {
                        b.tvYandexStatus.text = getString(R.string.telemost_yandex_required)
                        b.btnConnectYandex.text = getString(R.string.telemost_connect_yandex)
                        b.btnConnectYandex.isEnabled = true
                    } else {
                        b.tvYandexStatus.text = getString(R.string.telemost_yandex_connected)
                        b.btnConnectYandex.text = "✓"
                        b.btnConnectYandex.isEnabled = false
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                srvVm.status.collect { status ->
                    val cap = vm.estimateCapacity(status)
                    b.tvCapacity.text = if (cap != null && status != null) {
                        getString(
                            R.string.telemost_capacity_known,
                            cap,
                            status.memFreeMb,
                            status.cpuCount,
                        )
                    } else {
                        getString(R.string.telemost_capacity_unknown)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { st -> render(st) }
            }
        }
    }

    private fun renderCount(count: Int) {
        b.tvCount.text = count.toString()
        b.tvWarning.text = when {
            count <= 3 -> getString(R.string.telemost_warn_low, count * 2)
            count <= 6 -> getString(R.string.telemost_warn_medium)
            else -> getString(R.string.telemost_warn_high)
        }
    }

    private fun render(st: CreateTelemostViewModel.State) {
        when (st) {
            CreateTelemostViewModel.State.Idle -> {
                b.progressBlock.visibility = View.GONE
                b.btnCreate.isEnabled = true
                b.btnCreate.text = getString(R.string.telemost_create)
            }
            is CreateTelemostViewModel.State.Building -> {
                b.progressBlock.visibility = View.VISIBLE
                b.pbTelemost.isIndeterminate = true
                b.tvProgress.text = "→ ${st.step}"
                b.btnCreate.isEnabled = false
                b.btnCreate.text = getString(R.string.telemost_building)
            }
            is CreateTelemostViewModel.State.Success -> {
                b.progressBlock.visibility = View.GONE
                b.btnCreate.isEnabled = true
                b.btnCreate.text = getString(R.string.telemost_create)
                showSuccess(st.count, st.telemostUri)
                // Refresh the shared detail VM so the Telemost profile card
                // shows up on the Profiles tab immediately, not on next visit.
                srvVm.refreshTelemost()
                vm.resetIdle()
                dismiss()
            }
            is CreateTelemostViewModel.State.Failure -> {
                b.progressBlock.visibility = View.GONE
                b.btnCreate.isEnabled = true
                b.btnCreate.text = getString(R.string.telemost_create)
                showFailure(st.error)
                vm.resetIdle()
            }
        }
    }

    private fun showSuccess(count: Int, uri: String) {
        val activityCtx = requireActivity()
        MaterialAlertDialogBuilder(activityCtx)
            .setTitle(R.string.telemost_ready)
            .setMessage(getString(R.string.telemost_ready_body, count) + "\n\n" + uri)
            .setPositiveButton(R.string.chain_copy_uri) { _, _ ->
                val cm = activityCtx.getSystemService(Context.CLIPBOARD_SERVICE) as
                    android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("telemost", uri))
                android.widget.Toast.makeText(
                    activityCtx, R.string.copied, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.srv_profile_ready_close, null)
            .show()
    }

    private fun showFailure(err: com.smarttools.netguard.agent.FriendlyError) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(err.title)
            .setMessage(err.body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    companion object { const val TAG = "CreateTelemostSheet" }
}
