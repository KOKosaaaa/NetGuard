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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarttools.netguard.R
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = SubAdapter(
            onUpdate = { viewModel.updateSubscription(it) },
            onDelete = { viewModel.deleteSubscription(it) },
            onShare = { sub -> shareSubscription(sub) }
        )

        binding.rvSubs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSubs.adapter = adapter

        binding.fabAddSub.setOnClickListener { showAddDialog() }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_scan_qr -> {
                    findNavController().navigate(R.id.action_subscriptions_to_qr_scan)
                    true
                }
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

    private fun showAddDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val etName = EditText(requireContext()).apply {
            hint = "Name"
            layout.addView(this)
        }
        val etUrl = EditText(requireContext()).apply {
            hint = "https://..."
            layout.addView(this)
        }

        val intervals = listOf("Disabled", "6 hours", "12 hours", "24 hours", "48 hours")
        val intervalValues = listOf(0, 6, 12, 24, 48)
        val spInterval = android.widget.Spinner(requireContext()).apply {
            adapter = android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                intervals
            )
            layout.addView(this)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_subscription)
            .setView(layout)
            .setPositiveButton(R.string.add) { _, _ ->
                val hours = intervalValues[spInterval.selectedItemPosition]
                viewModel.addSubscription(
                    etName.text.toString().trim(),
                    etUrl.text.toString().trim(),
                    hours
                )
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
    private val onShare: (Subscription) -> Unit
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
        holder.binding.tvSubUrl.text = sub.url
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
    }
}
