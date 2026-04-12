package com.smarttools.netguard.ui.profiles

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.recyclerview.widget.RecyclerView
import com.smarttools.netguard.model.ServerProfile

class SubscriptionGroupDecoration : RecyclerView.ItemDecoration() {

    companion object {
        val GROUP_COLORS = intArrayOf(
            0xFF7C4DFF.toInt(), // Purple
            0xFF00BCD4.toInt(), // Cyan
            0xFFFF9800.toInt(), // Orange
            0xFF4CAF50.toInt(), // Green
            0xFFE91E63.toInt(), // Pink
            0xFF3F51B5.toInt(), // Indigo
            0xFFFFEB3B.toInt(), // Yellow
            0xFF009688.toInt(), // Teal
        )

        private const val FRAME_CORNER_RADIUS = 20f
        private const val FRAME_STROKE_WIDTH = 4f
        private const val FRAME_PADDING = 12f
        private const val LABEL_TEXT_SIZE = 32f
        private const val LABEL_PADDING_TOP = 36f
        private const val LABEL_PADDING_BOTTOM = 8f
    }

    private var profiles: List<ServerProfile> = emptyList()
    private var subscriptionNames: Map<Long, String> = emptyMap()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = FRAME_STROKE_WIDTH
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LABEL_TEXT_SIZE
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setData(profiles: List<ServerProfile>, names: Map<Long, String>) {
        this.profiles = profiles
        this.subscriptionNames = names
    }

    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: android.view.View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION || position >= profiles.size) return

        val profile = profiles[position]
        if (profile.subscriptionId <= 0) return

        // First item in a subscription group gets extra top space for the label
        val isFirstInGroup = position == 0 ||
                profiles[position - 1].subscriptionId != profile.subscriptionId
        if (isFirstInGroup) {
            outRect.top = LABEL_PADDING_TOP.toInt() + LABEL_PADDING_BOTTOM.toInt()
        }

        // Side padding for all items in a group
        outRect.left = FRAME_PADDING.toInt()
        outRect.right = FRAME_PADDING.toInt()

        // Last item in group gets extra bottom
        val isLastInGroup = position == profiles.size - 1 ||
                profiles[position + 1].subscriptionId != profile.subscriptionId
        if (isLastInGroup) {
            outRect.bottom = FRAME_PADDING.toInt()
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (profiles.isEmpty()) return

        // Find groups of consecutive items with same subscriptionId > 0
        val groups = mutableListOf<SubscriptionGroup>()
        var i = 0
        while (i < profiles.size) {
            val subId = profiles[i].subscriptionId
            if (subId > 0) {
                val start = i
                while (i < profiles.size && profiles[i].subscriptionId == subId) {
                    i++
                }
                groups.add(SubscriptionGroup(subId, start, i - 1))
            } else {
                i++
            }
        }

        for (group in groups) {
            drawGroup(c, parent, group)
        }
    }

    private fun drawGroup(c: Canvas, parent: RecyclerView, group: SubscriptionGroup) {
        // Find the first and last visible view in this group
        var topView: android.view.View? = null
        var bottomView: android.view.View? = null

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            if (pos in group.startPos..group.endPos) {
                if (topView == null || child.top < topView.top) topView = child
                if (bottomView == null || child.bottom > bottomView.bottom) bottomView = child
            }
        }

        if (topView == null || bottomView == null) return

        val colorIdx = (group.subscriptionId % GROUP_COLORS.size).toInt()
        val color = GROUP_COLORS[colorIdx]

        val left = parent.paddingLeft + FRAME_PADDING / 2
        val right = parent.width - parent.paddingRight - FRAME_PADDING / 2
        val top = topView.top - LABEL_PADDING_TOP - LABEL_PADDING_BOTTOM - FRAME_PADDING / 2
        val bottom = bottomView.bottom + FRAME_PADDING

        val rect = RectF(left, top, right, bottom)

        // Draw semi-transparent fill
        fillPaint.color = color
        fillPaint.alpha = 20
        c.drawRoundRect(rect, FRAME_CORNER_RADIUS, FRAME_CORNER_RADIUS, fillPaint)

        // Draw stroke border
        strokePaint.color = color
        strokePaint.alpha = 180
        c.drawRoundRect(rect, FRAME_CORNER_RADIUS, FRAME_CORNER_RADIUS, strokePaint)

        // Draw subscription name label
        val name = subscriptionNames[group.subscriptionId]
            ?: "Subscription #${group.subscriptionId}"
        textPaint.color = color
        textPaint.alpha = 220
        c.drawText(
            name,
            left + FRAME_PADDING + 4,
            topView.top - LABEL_PADDING_BOTTOM - 4,
            textPaint
        )
    }

    private data class SubscriptionGroup(
        val subscriptionId: Long,
        val startPos: Int,
        val endPos: Int
    )
}
