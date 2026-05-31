package com.smarttools.netguard.ui.profiles

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView

/**
 * Draws a colored rounded frame around each subscription group (header row +
 * its profile rows). Frame stays anchored to the actual group bounds even
 * during scroll: when the group's first or last view is offscreen, we
 * extrapolate the frame edge well past the viewport so the box doesn't
 * collapse onto the visible subset.
 */
class SubscriptionGroupDecoration : RecyclerView.ItemDecoration() {

    companion object {
        // Bright palette — reads well on DARK surfaces (default/OLED/Ocean/
        // Fsociety themes). Used as both the group-frame stroke and the
        // header-name text color.
        val GROUP_COLORS = intArrayOf(
            0xFF7C4DFF.toInt(),
            0xFF00BCD4.toInt(),
            0xFFFF9800.toInt(),
            0xFF4CAF50.toInt(),
            0xFFE91E63.toInt(),
            0xFF3F51B5.toInt(),
            0xFFFFEB3B.toInt(),
            0xFF009688.toInt(),
        )

        // Darker variants of the SAME hues (same order) for LIGHT surfaces —
        // the bright ones (yellow/cyan/green) are near-invisible as text on
        // white. Picked for >=4:1 contrast on the light theme's #FFFBFE.
        private val GROUP_COLORS_LIGHT = intArrayOf(
            0xFF5E35B1.toInt(), // deep purple
            0xFF00838F.toInt(), // cyan 800
            0xFFE65100.toInt(), // orange 900
            0xFF2E7D32.toInt(), // green 800
            0xFFC2185B.toInt(), // pink 700
            0xFF283593.toInt(), // indigo 800
            0xFFF57F17.toInt(), // amber 900 (dark, readable yellow)
            0xFF00695C.toInt(), // teal 800
        )

        /** Per-subscription accent color, chosen by the current theme's
         *  surface luminance so it stays readable on light AND dark themes. */
        fun colorFor(context: android.content.Context, subId: Long): Int {
            val palette = if (isLightSurface(context)) GROUP_COLORS_LIGHT else GROUP_COLORS
            return palette[(subId % palette.size).toInt()]
        }

        private fun isLightSurface(context: android.content.Context): Boolean {
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurface, tv, true)
            val c = tv.data
            val lum = 0.299 * android.graphics.Color.red(c) +
                0.587 * android.graphics.Color.green(c) +
                0.114 * android.graphics.Color.blue(c)
            return lum > 150
        }

        private const val FRAME_CORNER_RADIUS = 20f
        private const val FRAME_STROKE_WIDTH = 4f
        private const val FRAME_PADDING = 12f
        // How far past the viewport to draw a frame edge when the group
        // continues offscreen — large enough that no rounded corner is
        // ever visible on the wrong side.
        private const val EXTRAPOLATE_PX = 4000f
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = FRAME_STROKE_WIDTH
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: android.view.View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return

        val adapter = parent.adapter as? ProfileAdapter ?: return
        val item = adapter.itemAt(position) ?: return
        val groupSubId: Long = subIdOf(item) ?: return
        if (groupSubId <= 0) return

        outRect.left = FRAME_PADDING.toInt()
        outRect.right = FRAME_PADDING.toInt()

        val nextItem = adapter.itemAt(position + 1)
        val nextSubId = nextItem?.let { subIdOf(it) }
        if (nextSubId != groupSubId) {
            outRect.bottom = FRAME_PADDING.toInt()
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? ProfileAdapter ?: return
        val itemCount = adapter.itemCount
        if (itemCount == 0) return

        // Walk the full adapter list (not just onscreen children) and build
        // group bounds (firstPos..lastPos) by subscriptionId.
        var i = 0
        while (i < itemCount) {
            val item = adapter.itemAt(i)
            if (item == null) { i++; continue }
            val subId = subIdOf(item) ?: -1L
            if (subId <= 0) { i++; continue }

            val firstPos = i
            var lastPos = i
            i++
            while (i < itemCount) {
                val nextItem = adapter.itemAt(i) ?: break
                val nextSubId = subIdOf(nextItem) ?: -1L
                if (nextSubId != subId) break
                lastPos = i
                i++
            }

            // If neither end of the group is on screen, skip drawing entirely.
            val topVH = parent.findViewHolderForAdapterPosition(firstPos)
            val botVH = parent.findViewHolderForAdapterPosition(lastPos)

            // Also account for groups that span the entire viewport (both
            // edges offscreen) — find ANY visible child belonging to this
            // group; if at least one is visible, the box must be drawn
            // (with both edges extrapolated).
            var anyVisible = topVH != null || botVH != null
            if (!anyVisible) {
                for (k in firstPos..lastPos) {
                    if (parent.findViewHolderForAdapterPosition(k) != null) {
                        anyVisible = true; break
                    }
                }
            }
            if (!anyVisible) continue

            val top = topVH?.itemView?.top?.toFloat() ?: -EXTRAPOLATE_PX
            val bottom = botVH?.itemView?.bottom?.toFloat() ?: (parent.height + EXTRAPOLATE_PX)

            drawFrame(c, parent, top, bottom, subId)
        }
    }

    private fun drawFrame(c: Canvas, parent: RecyclerView,
                          rawTop: Float, rawBottom: Float, subId: Long) {
        val color = colorFor(parent.context, subId)

        val left = parent.paddingLeft + FRAME_PADDING / 2
        val right = parent.width - parent.paddingRight - FRAME_PADDING / 2
        val top = rawTop - FRAME_PADDING / 2
        val bottom = rawBottom + FRAME_PADDING / 2

        val rect = RectF(left, top, right, bottom)

        fillPaint.color = color
        fillPaint.alpha = 20
        c.drawRoundRect(rect, FRAME_CORNER_RADIUS, FRAME_CORNER_RADIUS, fillPaint)

        strokePaint.color = color
        strokePaint.alpha = 180
        c.drawRoundRect(rect, FRAME_CORNER_RADIUS, FRAME_CORNER_RADIUS, strokePaint)
    }

    private fun subIdOf(item: ProfileAdapter.Item): Long? = when (item) {
        is ProfileAdapter.Item.Header -> item.sub.id
        is ProfileAdapter.Item.Profile -> item.profile.subscriptionId.takeIf { it > 0 }
    }
}
