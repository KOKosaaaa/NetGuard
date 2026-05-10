package com.smarttools.netguard.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentLogBinding
import com.smarttools.netguard.databinding.ItemLogBinding
import com.smarttools.netguard.service.LogBuffer
import kotlinx.coroutines.launch

class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: LogAdapter
    private var currentFilter: LogBuffer.LogLevel? = null
    private var allEntries = listOf<LogBuffer.LogEntry>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = LogAdapter()
        binding.rvLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLogs.adapter = adapter

        // ChipGroup.setOnCheckedStateChangeListener fires for both check AND
        // uncheck. setOnClickListener on each chip only knew "user tapped me",
        // not whether the result was selected or deselected — so deselecting
        // Error still re-applied the Error filter.
        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val level = when (checkedIds.firstOrNull()) {
                R.id.chip_info -> LogBuffer.LogLevel.INFO
                R.id.chip_warn -> LogBuffer.LogLevel.WARN
                R.id.chip_error -> LogBuffer.LogLevel.ERROR
                else -> null
            }
            filterBy(level)
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear_logs -> {
                    LogBuffer.clear()
                    true
                }
                R.id.action_copy_logs -> {
                    val filtered = applyFilter(allEntries)
                    val text = filtered.joinToString("\n") { "[${it.level}] ${it.message}" }
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
                    Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LogBuffer.flow.collect { entries ->
                    allEntries = entries
                    val filtered = applyFilter(entries)
                    adapter.submitList(filtered)
                    if (filtered.isNotEmpty()) {
                        binding.rvLogs.scrollToPosition(filtered.size - 1)
                    }
                }
            }
        }
    }

    private fun filterBy(level: LogBuffer.LogLevel?) {
        currentFilter = level
        val filtered = applyFilter(allEntries)
        adapter.submitList(filtered)
        if (filtered.isNotEmpty()) {
            binding.rvLogs.scrollToPosition(filtered.size - 1)
        }
    }

    private fun applyFilter(entries: List<LogBuffer.LogEntry>): List<LogBuffer.LogEntry> {
        return if (currentFilter != null) {
            entries.filter { it.level == currentFilter }
        } else entries
    }

    inner class LogAdapter : androidx.recyclerview.widget.ListAdapter<LogBuffer.LogEntry, LogAdapter.VH>(
        object : DiffUtil.ItemCallback<LogBuffer.LogEntry>() {
            override fun areItemsTheSame(a: LogBuffer.LogEntry, b: LogBuffer.LogEntry) =
                a.timestamp == b.timestamp && a.message == b.message
            override fun areContentsTheSame(a: LogBuffer.LogEntry, b: LogBuffer.LogEntry) = a == b
        }
    ) {
        inner class VH(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = getItem(position)
            holder.binding.tvLogMessage.text = entry.message
            holder.binding.tvLogTime.text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(entry.timestamp))

            val color = when (entry.level) {
                LogBuffer.LogLevel.INFO -> ContextCompat.getColor(holder.itemView.context, R.color.log_info)
                LogBuffer.LogLevel.WARN -> ContextCompat.getColor(holder.itemView.context, R.color.log_warn)
                LogBuffer.LogLevel.ERROR -> ContextCompat.getColor(holder.itemView.context, R.color.log_error)
            }
            holder.binding.tvLogMessage.setTextColor(color)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
