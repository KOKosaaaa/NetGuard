package com.smarttools.netguard.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.smarttools.netguard.repository.StatsRepository
import com.smarttools.netguard.util.TrafficFormatter

class TrafficChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: List<StatsRepository.DayTraffic> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val emptyBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val barRect = RectF()

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    fun setData(history: List<StatsRepository.DayTraffic>) {
        data = history
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(160f).toInt()
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val h = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(heightSize)
            else -> desiredHeight
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val primaryColor = resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
        val surfaceVariantColor = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        val textColor = resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val subtextColor = resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        barPaint.color = primaryColor
        emptyBarPaint.color = surfaceVariantColor
        labelPaint.color = subtextColor
        labelPaint.textSize = sp(11f)
        valuePaint.color = textColor
        valuePaint.textSize = sp(9f)

        val maxVal = data.maxOfOrNull { it.total } ?: 0L
        val labelHeight = sp(11f) + dp(6f)
        val valueHeight = sp(9f) + dp(4f)
        val topPadding = valueHeight + dp(2f)
        val chartHeight = height - labelHeight - topPadding
        val barWidth = width.toFloat() / data.size
        val barInset = barWidth * 0.15f
        val barRadius = dp(4f)
        val minBarHeight = dp(4f)

        data.forEachIndexed { i, day ->
            val left = barWidth * i + barInset
            val right = barWidth * (i + 1) - barInset
            val cx = barWidth * i + barWidth / 2

            if (maxVal > 0 && day.total > 0) {
                val fraction = day.total.toFloat() / maxVal
                val barH = (chartHeight * fraction).coerceAtLeast(minBarHeight)
                val top = topPadding + (chartHeight - barH)
                barRect.set(left, top, right, topPadding + chartHeight)
                canvas.drawRoundRect(barRect, barRadius, barRadius, barPaint)

                // Value above bar
                canvas.drawText(
                    TrafficFormatter.formatBytes(day.total),
                    cx,
                    top - dp(3f),
                    valuePaint
                )
            } else {
                // Empty bar placeholder
                barRect.set(left, topPadding + chartHeight - minBarHeight, right, topPadding + chartHeight)
                canvas.drawRoundRect(barRect, barRadius, barRadius, emptyBarPaint)
            }

            // Day label
            canvas.drawText(day.dayOfWeek, cx, height.toFloat() - dp(2f), labelPaint)
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0xFF888888.toInt())
        ta.recycle()
        return color
    }
}
