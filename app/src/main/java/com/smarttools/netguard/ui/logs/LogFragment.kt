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
import androidx.core.view.doOnPreDraw
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
import com.smarttools.netguard.service.RouteDiagnostics
import com.smarttools.netguard.App
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

        binding.btnRouteDiagnostics.setOnClickListener { showDiagnostics() }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear_logs -> {
                    LogBuffer.clear()
                    RouteDiagnostics.shared.clear()
                    true
                }
                R.id.action_copy_logs -> {
                    copyLogs(applyFilter(LogBuffer.snapshot()))
                    true
                }
                R.id.action_save_logs -> {
                    saveLogsToFile()
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

    private fun copyLogs(entries: List<LogBuffer.LogEntry>) {
        val text = diagnosticText(true) + "\n--- LOG ---\n" +
            entries.joinToString("\n") { "[${it.level}] ${it.message}" }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Writes the FULL log buffer (unfiltered — diagnostics need every line) to
     * a timestamped .txt under the app's external files dir, then opens a share
     * sheet via FileProvider so it can be sent to Telegram / saved. No storage
     * permission needed: the file lives in app-private external storage and is
     * handed out only as a granted content:// URI.
     */
    private fun saveLogsToFile() {
        val ctx = requireContext()
        try {
            val tsFile = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val tsLine = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val text = buildString {
                append(diagnosticText(true)).append("\n--- LOG ---\n")
                for (e in LogBuffer.snapshot()) {
                    append(tsLine.format(java.util.Date(e.timestamp)))
                    append(" [").append(e.level).append("] ")
                    append(e.message).append('\n')
                }
            }
            val dir = java.io.File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val file = java.io.File(dir, "netguard-log-$tsFile.txt")
            file.writeText(text)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(share, getString(R.string.share_logs)))
            Toast.makeText(ctx, getString(R.string.logs_saved, file.name), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, getString(R.string.logs_save_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun diagnosticText(redact: Boolean): String = RouteDiagnostics.shared.render(
        resources.configuration.locales[0].language == "ru", redact,
        (requireContext().applicationContext as App).loadSettings().localDpiEnabled
    )

    private fun showDiagnostics() {
        diagnosticDialog?.dismiss()
        val dialog = DiagnosticsSheet(requireActivity() as com.smarttools.netguard.MainActivity,
            copy = { copyLogs(LogBuffer.snapshot()) }, save = { saveLogsToFile() })
        fun refresh() {
            val dpi = (requireContext().applicationContext as App).loadSettings().localDpiEnabled
            dialog.update(RouteDiagnostics.shared.presentation(requireContext(), dpi),
                com.smarttools.netguard.service.TunnelVpnService.connectionState.value is com.smarttools.netguard.model.ConnectionState.Connected)
        }
        refresh()
        dialog.show()
        diagnosticDialog = dialog
        val updates = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive && dialog.isShowing) {
                    delay(1000)
                    if (_binding != null && isAdded) refresh()
                }
            }
        }
        dialog.setOnDismissListener { updates.cancel(); if (diagnosticDialog === dialog) diagnosticDialog = null }
    }

    private var diagnosticDialog: android.app.Dialog? = null

    /** TextView's selectable text requests focus after setText/layout, which
     * otherwise makes ScrollView jump to the start every refresh. */
    private class DiagnosticScrollView(context: Context) : android.widget.ScrollView(context) {
        private var updating = false
        private var touching = false
        private var lastScrollAt = 0L

        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> touching = true
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> touching = false
            }
            return super.dispatchTouchEvent(event)
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            lastScrollAt = android.os.SystemClock.uptimeMillis()
        }

        override fun requestChildFocus(child: View, focused: View) {
            if (!updating) super.requestChildFocus(child, focused)
        }

        override fun requestChildRectangleOnScreen(child: View, rectangle: android.graphics.Rect, immediate: Boolean): Boolean {
            // Selectable TextView brings its cursor into view on pre-draw,
            // after layout. That request must not move a reader on refresh.
            return !updating && super.requestChildRectangleOnScreen(child, rectangle, immediate)
        }

        fun updateText(text: android.widget.TextView, value: String) {
            // Do not interfere with dragging, a fling, or text selection.
            if (updating || touching || android.os.SystemClock.uptimeMillis() - lastScrollAt < 250 ||
                text.text.toString() == value) return
            val previousY = scrollY
            updating = true
            text.text = value
            doOnPreDraw {
                scrollTo(0, previousY)
                // TextView may register its own pre-draw listener during
                // measure, after ours. Keep the guard for the entire frame.
                post { updating = false }
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
        diagnosticDialog?.dismiss(); diagnosticDialog = null
        _binding = null
        super.onDestroyView()
    }
}
