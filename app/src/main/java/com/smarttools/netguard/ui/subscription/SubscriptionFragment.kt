package com.smarttools.netguard.ui.subscription

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.DialogAddSubscriptionBinding
import com.smarttools.netguard.databinding.FragmentSubscriptionBinding
import com.smarttools.netguard.model.Subscription
import com.smarttools.netguard.util.QRGenerator
import com.smarttools.netguard.viewmodel.ProfileListViewModel
import com.smarttools.netguard.viewmodel.SubscriptionViewModel
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch

class SubscriptionFragment : Fragment() {

    private var _binding: FragmentSubscriptionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SubscriptionViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSubscriptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // Refresh stale subscriptions silently so the "updated at"
        // timestamp on each card stays current. Bounded to >1h since
        // last fetch — frequent tab switches don't hammer the provider.
        viewModel.refreshIfStale()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = SubAdapter(
            onUpdate = { viewModel.updateSubscription(it) },
            onDelete = { viewModel.deleteSubscription(it) },
            onShare = { sub -> shareSubscription(sub) },
            onRename = { sub -> showRenameDialog(sub) }
        )

        binding.rvSubs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSubs.adapter = adapter

        binding.fabAddSub.setOnClickListener { showAddSourceChooser() }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_update_all -> {
                    viewModel.updateAll()
                    true
                }
                else -> false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.subscriptions.collect { subs ->
                        adapter.submitList(subs)
                        binding.tvEmpty.visibility = if (subs.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.updating.collect { updating ->
                        binding.progress.visibility = if (updating) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.message.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        // Drop the replay cache so re-entering the tab
                        // doesn't re-show the same "Updated N profiles"
                        // toast over and over.
                        viewModel.consumeMessage()
                    }
                }
            }
        }
    }

    private fun shareSubscription(sub: Subscription) {
        val items = arrayOf(
            getString(R.string.share_qr),    // QR code
            getString(R.string.share_uri),   // Copy URL
            getString(R.string.share)        // Share via...
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(sub.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSubscriptionQR(sub)
                    1 -> {
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("subscription", sub.url))
                        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, sub.name)
                            putExtra(Intent.EXTRA_TEXT, sub.url)
                        }
                        startActivity(Intent.createChooser(intent, getString(R.string.share)))
                    }
                }
            }
            .show()
    }

    private fun showSubscriptionQR(sub: Subscription) {
        if (sub.url.isBlank()) {
            Toast.makeText(requireContext(), "Subscription URL is empty", Toast.LENGTH_SHORT).show()
            return
        }
        if (sub.url.length > 2953) {
            Toast.makeText(requireContext(), "URL too long for QR code", Toast.LENGTH_SHORT).show()
            return
        }
        val qrBitmap = QRGenerator.generate(sub.url, 600)
        val imageView = ImageView(requireContext()).apply {
            setImageBitmap(qrBitmap)
            setPadding(48, 48, 48, 16)
            adjustViewBounds = true
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(sub.name)
            .setView(imageView)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.share_uri) { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("subscription", sub.url))
                Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Triggered from the "+" FAB. Asks whether the user wants to add a
     * subscription by pasting a link (the existing stateful import
     * dialog) or by scanning a QR code (existing QR-scan fragment).
     * Consolidates two top-of-screen actions into one bottom action.
     */
    private fun showAddSourceChooser() {
        val items = arrayOf(
            getString(R.string.add_by_link),
            getString(R.string.scan_qr),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_subscription)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAddDialog()
                    1 -> findNavController().navigate(R.id.action_subscriptions_to_qr_scan)
                }
            }
            .show()
    }

    private fun showAddDialog() {
        val dlgBinding = DialogAddSubscriptionBinding.inflate(layoutInflater)

        val intervals = listOf("Disabled", "6 hours", "12 hours", "24 hours", "48 hours")
        val intervalValues = listOf(0, 6, 12, 24, 48)
        dlgBinding.spInterval.adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            intervals
        )

        // Pre-fill URL from clipboard if it looks like a subscription/profile
        // link — saves the user one paste step. Recognise https://, the
        // proxy-profile schemes (vless/vmess/trojan/ss/hysteria2/hy2) and bare
        // Telemost join links, since the ViewModel handles all of these.
        runCatching {
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && looksLikeSubscriptionInput(text)) {
                dlgBinding.etUrl.setText(text)
            }
        }

        val dialog: AlertDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_subscription)
            .setView(dlgBinding.root)
            .setCancelable(true)
            .create()

        fun setState(state: ImportDialogState) {
            dlgBinding.stateInput.visibility = if (state == ImportDialogState.INPUT) View.VISIBLE else View.GONE
            dlgBinding.stateLoading.visibility = if (state == ImportDialogState.LOADING) View.VISIBLE else View.GONE
            dlgBinding.stateSuccess.visibility = if (state == ImportDialogState.SUCCESS) View.VISIBLE else View.GONE
            dlgBinding.stateError.visibility = if (state == ImportDialogState.ERROR) View.VISIBLE else View.GONE
            dlgBinding.buttonBarInput.visibility = if (state == ImportDialogState.INPUT) View.VISIBLE else View.GONE
            dlgBinding.buttonBarSuccess.visibility = if (state == ImportDialogState.SUCCESS) View.VISIBLE else View.GONE
            dlgBinding.buttonBarError.visibility = if (state == ImportDialogState.ERROR) View.VISIBLE else View.GONE
            // Lock back / outside-tap during the network request so the user
            // can't dismiss the dialog mid-import and lose the result toast.
            dialog.setCancelable(state != ImportDialogState.LOADING)
        }

        fun runImport() {
            val name = dlgBinding.etName.text?.toString()?.trim().orEmpty()
            val url = dlgBinding.etUrl.text?.toString()?.trim().orEmpty()
            val hours = intervalValues[dlgBinding.spInterval.selectedItemPosition]
            // Drop the soft keyboard so the loading spinner / success card isn't
            // hidden behind the IME.
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(dlgBinding.etUrl.windowToken, 0)
            setState(ImportDialogState.LOADING)
            viewLifecycleOwner.lifecycleScope.launch {
                val result = viewModel.importSubscription(name, url, hours)
                result.fold(
                    onSuccess = { info ->
                        dlgBinding.tvSuccess.text = getString(
                            R.string.import_success_format, info.profileCount, info.name
                        )
                        setState(ImportDialogState.SUCCESS)
                    },
                    onFailure = { error ->
                        dlgBinding.tvError.text = getString(
                            R.string.import_error_format, error.message ?: error.javaClass.simpleName
                        )
                        setState(ImportDialogState.ERROR)
                    }
                )
            }
        }

        dlgBinding.btnImport.setOnClickListener { runImport() }
        dlgBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dlgBinding.btnRetry.setOnClickListener { setState(ImportDialogState.INPUT) }
        dlgBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dlgBinding.btnAddMore.setOnClickListener {
            dlgBinding.etName.setText("")
            dlgBinding.etUrl.setText("")
            dlgBinding.spInterval.setSelection(0)
            setState(ImportDialogState.INPUT)
        }
        dlgBinding.btnContinue.setOnClickListener {
            dialog.dismiss()
            // Jump straight to the profile list so the user sees what was imported.
            findNavController().navigate(R.id.nav_profiles)
        }

        setState(ImportDialogState.INPUT)
        dialog.show()
    }

    private enum class ImportDialogState { INPUT, LOADING, SUCCESS, ERROR }

    private fun looksLikeSubscriptionInput(text: String): Boolean {
        if (text.startsWith("https://")) return true
        val schemes = listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "telemost://")
        return schemes.any { text.startsWith(it) }
    }

    private fun showRenameDialog(sub: Subscription) {
        val et = EditText(requireContext()).apply {
            setText(sub.name)
            setSelection(sub.name.length)
            hint = getString(R.string.subscription_name_hint)
            // Keep the row sane — same 256-char cap as ViewModel.addSubscription.
            filters = arrayOf(android.text.InputFilter.LengthFilter(128))
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
            addView(et)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.subscription_rename_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = et.text.toString().trim().ifBlank { sub.name }
                viewModel.renameSubscription(sub, newName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

// Simple RecyclerView adapter for subscriptions
class SubAdapter(
    private val onUpdate: (Subscription) -> Unit,
    private val onDelete: (Subscription) -> Unit,
    private val onShare: (Subscription) -> Unit,
    private val onRename: (Subscription) -> Unit
) : androidx.recyclerview.widget.ListAdapter<Subscription, SubAdapter.VH>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<Subscription>() {
        override fun areItemsTheSame(a: Subscription, b: Subscription) = a.id == b.id
        override fun areContentsTheSame(a: Subscription, b: Subscription) = a == b
    }
) {
    inner class VH(val binding: com.smarttools.netguard.databinding.ItemSubscriptionBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = com.smarttools.netguard.databinding.ItemSubscriptionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val sub = getItem(position)
        val ctx = holder.binding.root.context
        holder.binding.tvSubName.text = sub.name
        // Subscription URLs typically embed a per-user token in the path
        // (e.g. provider.com/api/<token>). Rendering the full URL in a
        // RecyclerView leaks the token to anyone who screenshots / records the
        // screen. Show host + a 4-char tail as a hint instead.
        holder.binding.tvSubUrl.text = maskSubscriptionUrl(sub.url)
        holder.binding.tvSubCount.text = "${sub.profileCount} profiles"
        holder.binding.tvSubUpdated.text = if (sub.lastUpdatedMs > 0) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(sub.lastUpdatedMs))
        } else "-"
        if (sub.expireMs > 0) {
            val date = java.text.SimpleDateFormat("d.MM.yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(sub.expireMs))
            val expired = sub.expireMs < System.currentTimeMillis()
            val resId = if (expired) R.string.sub_expired else R.string.sub_expires_at
            holder.binding.tvSubExpire.text = ctx.getString(resId, date)
            holder.binding.tvSubExpire.visibility = View.VISIBLE
        } else {
            holder.binding.tvSubExpire.visibility = View.GONE
        }
        holder.binding.btnShare.setOnClickListener { onShare(sub) }
        holder.binding.btnUpdate.setOnClickListener { onUpdate(sub) }
        holder.binding.btnDelete.setOnClickListener { onDelete(sub) }
        // Long-press anywhere on the subscription row → rename. Marks the
        // subscription as user-renamed so the next refresh keeps the chosen
        // name instead of overwriting it from the server's profile-title.
        holder.binding.root.setOnLongClickListener {
            onRename(sub)
            true
        }
    }

    private fun maskSubscriptionUrl(url: String): String {
        return try {
            val parsed = java.net.URL(url)
            val host = parsed.host
            val path = parsed.path
            if (path.isBlank() || path == "/") {
                host
            } else {
                val suffix = path.takeLast(4)
                "$host/…/$suffix"
            }
        } catch (_: Exception) {
            if (url.length > 30) url.take(30) + "…" else url
        }
    }
}
