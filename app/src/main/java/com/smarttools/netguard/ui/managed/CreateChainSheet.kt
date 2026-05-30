package com.smarttools.netguard.ui.managed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarttools.netguard.R
import com.smarttools.netguard.agent.ManagedServer
import com.smarttools.netguard.databinding.BottomSheetCreateChainBinding
import com.smarttools.netguard.databinding.ItemChainServerRowBinding
import kotlinx.coroutines.launch

/**
 * "Новый маршрут" bottom sheet. Two RecyclerViews:
 *   - rv_route: ordered hops (entry → exit, index labels 1, 2, 3, …)
 *   - rv_pool: available managed servers, tap → add to bottom of route
 *
 * Button states are driven by [CreateChainViewModel.State]; the sheet
 * stays open during build (covered by a tiny inline progress overlay
 * inside the button text) so the user sees the result without losing
 * scroll position.
 */
class CreateChainSheet : BottomSheetDialogFragment() {

    private var _b: BottomSheetCreateChainBinding? = null
    private val b get() = _b!!
    private val vm: CreateChainViewModel by activityViewModels()

    private lateinit var routeAdapter: RowAdapter
    private lateinit var poolAdapter: RowAdapter
    private lateinit var routeTouchHelper: ItemTouchHelper

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = BottomSheetCreateChainBinding.inflate(i, c, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        // Soft keyboard otherwise covers the bottom inputs (label/SNI)
        // when the user taps to type. Resize the sheet's window so the
        // content scrolls instead.
        dialog?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        // Expand the bottom sheet to full height instead of the default
        // half-screen peek — the form is long (two lists + two inputs +
        // button) and the user shouldn't have to drag it up.
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
            ?: return
        val sheet = dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        // Disable drag-to-dismiss on the sheet itself. ItemTouchHelper
        // listens for vertical touches to reorder hops; if the sheet is
        // also draggable, dragging a hop past the last row drags the
        // *whole sheet* down instead of completing the swap. Closing is
        // still supported via the scrim tap.
        behavior.isDraggable = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        routeAdapter = RowAdapter(Mode.ROUTE) { vm.removeFromRoute(it) }
        poolAdapter = RowAdapter(Mode.POOL) { vm.addToRoute(it) }

        b.rvRoute.layoutManager = LinearLayoutManager(requireContext())
        b.rvRoute.adapter = routeAdapter
        b.rvPool.layoutManager = LinearLayoutManager(requireContext())
        b.rvPool.adapter = poolAdapter

        // Drag-to-reorder for the Route list only. We disable
        // long-press drag so the user can scroll the sheet by holding
        // anywhere; the actual drag is started from the handle's
        // onTouch callback (configured in the adapter).
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = false

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                @Suppress("DEPRECATION")
                vm.moveInRoute(vh.adapterPosition, target.adapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit
        }
        routeTouchHelper = ItemTouchHelper(callback)
        routeTouchHelper.attachToRecyclerView(b.rvRoute)
        routeAdapter.dragStarter = { vh -> routeTouchHelper.startDrag(vh) }

        b.btnBuild.setOnClickListener {
            val sni = b.etSni.text?.toString().orEmpty()
            val label = b.etLabel.text?.toString().orEmpty()
            vm.build(sni = sni, label = label)
        }

        // When the user taps the name/SNI inputs the soft keyboard takes
        // up ~half the screen. The earlier implementation used v.top
        // directly, but that's relative to the immediate parent
        // (TextInputLayout) — almost zero, so smoothScrollTo did
        // nothing. Walk the chain up to the NestedScrollView and sum
        // each hop's top so we get the absolute offset to scroll to.
        val scrollToInput: (View) -> Unit = { v ->
            v.postDelayed({
                val sv = _b?.scrollRoot ?: return@postDelayed
                var y = 0
                var node: View? = v
                while (node != null && node !== sv) {
                    y += node.top
                    node = node.parent as? View
                }
                // Leave headroom above so the floating label is visible
                // too, not just the EditText itself.
                sv.smoothScrollTo(0, (y - 80).coerceAtLeast(0))
            }, 300)
        }
        b.etLabel.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) scrollToInput(v) }
        b.etSni.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) scrollToInput(v) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.route.collect { list ->
                    routeAdapter.submitList(list.toList()) {
                        // submitList + DiffUtil only re-binds items whose
                        // *data* changed; an arrow on the previous "last"
                        // hop depends on POSITION, which Diff doesn't
                        // see. Force a full re-bind so the arrow on the
                        // now-not-last row hides itself.
                        routeAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.available.collect { list ->
                    poolAdapter.submitList(list.toList())
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { st -> render(st) }
            }
        }
    }

    private fun render(st: CreateChainViewModel.State) {
        when (st) {
            CreateChainViewModel.State.Idle -> {
                b.btnBuild.isEnabled = true
                b.btnBuild.text = getString(R.string.chain_build)
                b.progressBlock.visibility = View.GONE
            }
            is CreateChainViewModel.State.Building -> {
                b.btnBuild.isEnabled = false
                b.btnBuild.text = getString(R.string.chain_building)
                b.progressBlock.visibility = View.VISIBLE
                renderProgress(st.progress)
            }
            is CreateChainViewModel.State.Success -> {
                b.btnBuild.isEnabled = true
                b.btnBuild.text = getString(R.string.chain_build)
                b.progressBlock.visibility = View.GONE
                showSuccess(st.entryUri, st.label)
                vm.resetIdle()
                dismiss()
            }
            is CreateChainViewModel.State.Failure -> {
                b.btnBuild.isEnabled = true
                b.btnBuild.text = getString(R.string.chain_build)
                b.progressBlock.visibility = View.GONE
                showFailure(st.error)
                vm.resetIdle()
            }
        }
    }

    private fun renderProgress(p: com.smarttools.netguard.agent.ChainOrchestrator.Progress?) {
        if (p == null) {
            b.pbChain.isIndeterminate = true
            b.tvProgressStage.text = getString(R.string.chain_building)
            return
        }
        b.pbChain.isIndeterminate = false
        // Map: each hop contributes 1/N of the bar; stage within hop
        // contributes 1/4 of that hop's share (STARTING=0, DEPLOY=1,
        // ADDING=2, DONE=3). Gives a smooth-ish fill across the wizard.
        val stageFraction = when (p.stage) {
            com.smarttools.netguard.agent.ChainOrchestrator.Stage.STARTING -> 0
            com.smarttools.netguard.agent.ChainOrchestrator.Stage.DEPLOYING_XRAY -> 1
            com.smarttools.netguard.agent.ChainOrchestrator.Stage.ADDING_PROFILE -> 2
            com.smarttools.netguard.agent.ChainOrchestrator.Stage.DONE -> 3
        }
        val totalSteps = p.hopTotal * 4
        // hopIndex is a 1-based iteration counter (forward-counting),
        // so completed hops = hopIndex - 1.
        val completedSteps = (p.hopIndex - 1) * 4 + stageFraction
        b.pbChain.progress = (completedSteps * 100 / totalSteps).coerceIn(0, 100)

        val displayIndex = p.hopIndex
        val resId = when (p.stage) {
            com.smarttools.netguard.agent.ChainOrchestrator.Stage.STARTING -> R.string.chain_progress_starting
            com.smarttools.netguard.agent.ChainOrchestrator.Stage.DEPLOYING_XRAY -> R.string.chain_progress_deploying
            com.smarttools.netguard.agent.ChainOrchestrator.Stage.ADDING_PROFILE -> R.string.chain_progress_adding
            com.smarttools.netguard.agent.ChainOrchestrator.Stage.DONE -> R.string.chain_progress_done
        }
        // Two-line layout: top stays stable so the user's eye doesn't
        // jump every time a sub-step swaps in.
        b.tvProgressStage.text = getString(resId, displayIndex, p.hopTotal, p.serverName)
        b.tvProgressSubstep.text =
            if (p.stage == com.smarttools.netguard.agent.ChainOrchestrator.Stage.DEPLOYING_XRAY) {
                substepText(p.subStep)?.let { "→ $it" }.orEmpty()
            } else {
                ""
            }
    }

    private fun substepText(step: String?): String? {
        if (step.isNullOrEmpty()) return null
        val resId = when (step) {
            "detect" -> R.string.chain_substep_detect
            "prereqs" -> R.string.chain_substep_prereqs
            "download_xray", "download" -> R.string.chain_substep_download_xray
            "extract_xray" -> R.string.chain_substep_extract_xray
            "geo_dat" -> R.string.chain_substep_geo_dat
            "write_config" -> R.string.chain_substep_write_config
            "systemd_unit" -> R.string.chain_substep_systemd_unit
            "sysctl" -> R.string.chain_substep_sysctl
            "firewall" -> R.string.chain_substep_firewall
            "systemd_start" -> R.string.chain_substep_systemd_start
            "rollback" -> R.string.chain_substep_rollback
            "healthcheck" -> R.string.chain_substep_healthcheck
            else -> return step // unknown step — surface raw so we can iterate
        }
        return getString(resId)
    }

    private fun showSuccess(uri: String, label: String) {
        // CRITICAL: capture the Activity context BEFORE we dismiss the
        // sheet — `requireContext()` inside the dialog's button lambda
        // would crash because the fragment is no longer attached by the
        // time the user taps Copy.
        val activityCtx = requireActivity()
        val cm = activityCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        MaterialAlertDialogBuilder(activityCtx)
            .setTitle(R.string.chain_ready_title)
            .setMessage(getString(R.string.chain_ready_body) + "\n\n" + uri)
            .setPositiveButton(R.string.chain_copy_uri) { _, _ ->
                cm.setPrimaryClip(ClipData.newPlainText("vless", uri))
                Toast.makeText(activityCtx, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.srv_profile_ready_close, null)
            .show()
    }

    private fun showFailure(err: com.smarttools.netguard.agent.FriendlyError) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(err.title.ifEmpty { getString(R.string.chain_failed) })
            .setMessage(err.body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    enum class Mode { ROUTE, POOL }

    inner class RowAdapter(
        private val mode: Mode,
        private val onClick: (ManagedServer) -> Unit,
    ) : ListAdapter<ManagedServer, RowAdapter.VH>(DIFF) {

        /** Set by the host when an ItemTouchHelper is attached. Pool
         *  rows leave this null so the row can't be dragged. */
        var dragStarter: ((RecyclerView.ViewHolder) -> Unit)? = null

        inner class VH(val b: ItemChainServerRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
            VH(ItemChainServerRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false))

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = getItem(pos)
            with(h.b) {
                if (mode == Mode.ROUTE) {
                    tvIdx.text = "${pos + 1}."
                    btnAction.setIconResource(R.drawable.ic_delete)
                    // Arrow only between hops — not after the last one
                    // (it's the exit) and not in the pool (those rows
                    // aren't connected to anything).
                    ivArrowDown.visibility =
                        if (pos < itemCount - 1) View.VISIBLE else View.GONE
                    ivDragHandle.visibility = View.VISIBLE
                    // Only the handle starts a drag — touching the rest
                    // of the row does nothing. Drag fires on ACTION_DOWN
                    // so there's no perceived lag.
                    ivDragHandle.setOnTouchListener { _, ev ->
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                            dragStarter?.invoke(h)
                        }
                        false
                    }
                } else {
                    tvIdx.text = "+"
                    btnAction.setIconResource(R.drawable.ic_add)
                    ivArrowDown.visibility = View.GONE
                    ivDragHandle.visibility = View.GONE
                }
                // Click handlers only on the action icon — tapping
                // anywhere else on the plate is a no-op so the user
                // doesn't add/remove a server by mistake while
                // scrolling or aiming for the handle.
                btnAction.setOnClickListener { onClick(s) }
                root.setOnClickListener(null)
                root.isClickable = false
                tvName.text = s.name
                tvHost.text = "${s.host}:${s.port}"
            }
        }
    }

    companion object {
        const val TAG = "CreateChainSheet"
        private val DIFF = object : DiffUtil.ItemCallback<ManagedServer>() {
            override fun areItemsTheSame(a: ManagedServer, b: ManagedServer) = a.id == b.id
            override fun areContentsTheSame(a: ManagedServer, b: ManagedServer) = a == b
        }
    }
}
