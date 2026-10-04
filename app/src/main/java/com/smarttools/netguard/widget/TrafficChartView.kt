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

    // Cached dimensions (displayMetrics-dependent, refreshed on attach)
    private var sp11 = 0f
    private var sp9 = 0f
    private var dp2 = 0f
    private var dp3 = 0f
    private var dp4 = 0f
    private var dp6 = 0f
    private var dp160 = 0

    // Cached theme colors (refreshed on attach — one obtainStyledAttributes call for all four)
    private var primaryColor = 0
    private var surfaceVariantColor = 0
    private var textColor = 0
    private var subtextColor = 0

    init {
        resolveDimensions()
        resolveThemeColors()
        applyPaintStyles()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Theme can change between detach/attach (night mode toggle, DynamicColors switch)
        resolveThemeColors()
        applyPaintStyles()
    }

    private fun resolveDimensions() {
        val dm = resources.displayMetrics
        sp11 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, dm)
        sp9 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, dm)
        dp2 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, dm)
        dp3 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, dm)
        dp4 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, dm)
        dp6 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, dm)
        dp160 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 160f, dm).toInt()
    }

    private fun resolveThemeColors() {
        val ta = context.obtainStyledAttributes(com.smarttools.netguard.R.styleable.ThemePalette)
        try {
            primaryColor = ta.getColor(com.smarttools.netguard.R.styleable.ThemePalette_colorPrimary, 0xFF888888.toInt())
            surfaceVariantColor = ta.getColor(com.smarttools.netguard.R.styleable.ThemePalette_colorSurfaceVariant, 0xFF888888.toInt())
            textColor = ta.getColor(com.smarttools.netguard.R.styleable.ThemePalette_colorOnSurface, 0xFF888888.toInt())
            subtextColor = ta.getColor(com.smarttools.netguard.R.styleable.ThemePalette_colorOnSurfaceVariant, 0xFF888888.toInt())
        } finally {
            ta.recycle()
        }
    }

    private fun applyPaintStyles() {
        labelPaint.typeface=AppTypography.body(context)
        valuePaint.typeface=AppTypography.mono(context)
        barPaint.color = primaryColor
        emptyBarPaint.color = surfaceVariantColor
        labelPaint.color = subtextColor
        labelPaint.textSize = sp11
        valuePaint.color = textColor
        valuePaint.textSize = sp9
    }

    fun setData(history: List<StatsRepository.DayTraffic>) {
        data = history
        contentDescription = context.getString(com.smarttools.netguard.R.string.home_chart_accessibility,
            history.joinToString("; ") { "${it.dayOfWeek}: ${TrafficFormatter.formatBytes(it.total)}" })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val h = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> dp160.coerceAtMost(heightSize)
            else -> dp160
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val maxVal = data.maxOfOrNull { it.total } ?: 0L
        val peakIndex = data.indexOfFirst { it.total == maxVal }
        val labelHeight = sp11 + dp6
        val valueHeight = sp9 + dp4
        val topPadding = valueHeight + dp2
        val chartHeight = height - labelHeight - topPadding
        val barWidth = width.toFloat() / data.size
        val barInset = barWidth * 0.15f
        val minBarHeight = dp4

        data.forEachIndexed { i, day ->
            val left = barWidth * i + barInset
            val right = barWidth * (i + 1) - barInset
            val cx = barWidth * i + barWidth / 2

            if (maxVal > 0 && day.total > 0) {
                val fraction = day.total.toFloat() / maxVal
                val barH = (chartHeight * fraction).coerceAtLeast(minBarHeight)
                val top = topPadding + (chartHeight - barH)
                barRect.set(left, top, right, topPadding + chartHeight)
                canvas.drawRoundRect(barRect, dp4, dp4, barPaint)

                // A compact chart labels one peak; dense daily values overlap at large font sizes.
                if (i == peakIndex) {
                    val value = TrafficFormatter.formatBytes(day.total)
                    val halfText = valuePaint.measureText(value) / 2
                    val valueX = cx.coerceIn(halfText, (width - halfText).coerceAtLeast(halfText))
                    canvas.drawText(value, valueX, top - dp3, valuePaint)
                }
            } else {
                barRect.set(left, topPadding + chartHeight - minBarHeight, right, topPadding + chartHeight)
                canvas.drawRoundRect(barRect, dp4, dp4, emptyBarPaint)
            }

            canvas.drawText(day.dayOfWeek, cx, height.toFloat() - dp2, labelPaint)
        }
    }
}
