package com.smarttools.netguard.ui.logs

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.doOnLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.smarttools.netguard.MainActivity
import com.smarttools.netguard.R
import com.smarttools.netguard.service.RouteDiagnostics
import com.smarttools.netguard.widget.AppTypography
import com.smarttools.netguard.widget.GlassDrawable
import com.smarttools.netguard.widget.LiquidBackdrop

/** Fixed actions and a diffed, stable list: live accounting must not reset the reader's position. */
internal class DiagnosticsSheet(private val activity: MainActivity, copy: () -> Unit, save: () -> Unit) : BottomSheetDialog(activity) {
    private val d = context.resources.displayMetrics.density
    private fun dp(n: Int) = (n * d).toInt()
    private val backdrop = LiquidBackdrop(activity)
    private val status = label(12f)
    private val evidence = label(14f)
    private val expanded = mutableSetOf<String>()
    private val rows = Rows()
    private val list = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        adapter = rows
        itemAnimator = null
        clipToPadding = true
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(24))
        setPadding(dp(16), dp(8), dp(16), dp(12))
    }

    init {
        val frame = FrameLayout(context)
        frame.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))
        val head = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(8)) }
        head.addView(label(20f).apply { text = context.getString(R.string.route_diagnostics); typeface = AppTypography.heading(context) })
        head.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        head.addView(evidence, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val note = label(12f).apply {
            text = context.getString(R.string.diag_note)
        }
        if (context.resources.configuration.fontScale < 1.5f) head.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(head)
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(context).apply { setPadding(dp(12), dp(8), dp(12), dp(12)); orientation = LinearLayout.HORIZONTAL }
        listOf(context.getString(android.R.string.copy) to copy, context.getString(R.string.save) to save,
            context.getString(R.string.close) to { dismiss() }).forEach { (title, action) ->
            actions.addView(MaterialButton(context).apply {
                text = title; textSize = 12f; isAllCaps = false; minWidth = 0
                minimumHeight = dp(48); setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        content.addView(actions)
        setContentView(frame)
        setOnShowListener {
            val sheet = findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            behavior.skipCollapsed = true
            behavior.isDraggable = false // scrolling the report must not dismiss it
            // Dialog displayMetrics can retain the portrait dimensions after a
            // resize. Size against the laid-out window and its actual safe area.
            sheet.doOnLayout {
                val parent = sheet.parent as View
                val visible = android.graphics.Rect()
                val safe = android.graphics.Rect()
                parent.getGlobalVisibleRect(visible)
                parent.getWindowVisibleDisplayFrame(safe)
                // The Coordinator may already be inset. Intersect its bounds
                // with the safe window instead of subtracting the bars twice.
                val available = (if (visible.intersect(safe)) visible.height() else parent.height).coerceAtLeast(dp(100))
                val compact = available / d < 400
                val height = if (compact) available else minOf((parent.height * .9f).toInt(), available)
                if (compact) {
                    status.maxLines = 1
                    status.ellipsize = android.text.TextUtils.TruncateAt.END
                    head.setPadding(dp(20), dp(8), dp(20), dp(4))
                }
                sheet.layoutParams = sheet.layoutParams.apply { this.height = height }
                sheet.doOnLayout { behavior.state = BottomSheetBehavior.STATE_EXPANDED }
            }
            activity.decorateGlass(content)
        }
    }

    private fun label(size: Float) = TextView(context).apply {
        textSize = size; typeface = AppTypography.body(context); setTextColor(backdrop.foreground)
    }

    fun update(snapshot: RouteDiagnostics.UiSnapshot, connected: Boolean) {
        backdrop.setConnected(connected, false)
        status.text = snapshot.status
        evidence.text = snapshot.evidence
        if (snapshot.rows.isEmpty()) rows.submitList(listOf(RouteDiagnostics.UiRow("empty", context.getString(R.string.diag_empty),
            context.getString(R.string.diag_empty_action),
            context.getString(R.string.diag_no_network_action), "")))
        else rows.submitList(snapshot.rows)
    }

    private inner class Rows : ListAdapter<RouteDiagnostics.UiRow, Holder>(object : DiffUtil.ItemCallback<RouteDiagnostics.UiRow>() {
        override fun areItemsTheSame(a: RouteDiagnostics.UiRow, b: RouteDiagnostics.UiRow) = a.key == b.key
        override fun areContentsTheSame(a: RouteDiagnostics.UiRow, b: RouteDiagnostics.UiRow) = a == b
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                background = GlassDrawable(backdrop.light, backdrop.connectionLight > .5f, 22 * d, terminal = backdrop.terminal)
            }
            return Holder(card)
        }
        override fun onBindViewHolder(h: Holder, p: Int) {
            val row = getItem(p)
            h.title.text = row.title; h.probe.text = row.probe; h.traffic.text = row.traffic; h.detail.text = row.details
            h.detail.visibility = if (row.key in expanded) View.VISIBLE else View.GONE
            h.itemView.contentDescription = "${row.title}. ${row.probe}. ${row.traffic}"
            h.itemView.setOnClickListener {
                if (row.details.isNotEmpty()) {
                    if (!expanded.add(row.key)) expanded.remove(row.key)
                    h.detail.visibility = if (row.key in expanded) View.VISIBLE else View.GONE
                }
            }
        }
    }
    private inner class Holder(card: LinearLayout) : RecyclerView.ViewHolder(card) {
        val title = label(16f).apply { typeface = if (backdrop.terminal) AppTypography.mono(context) else AppTypography.heading(context) }
        val probe = label(12f).apply { setTextColor(backdrop.muted) }
        val traffic = label(13f)
        val detail = label(12f).apply { typeface = AppTypography.mono(context); setTextColor(backdrop.muted) }
        init {
            listOf(title, probe, traffic, detail).forEachIndexed { i, text ->
                card.addView(text, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(8) })
            }
        }
    }
}
