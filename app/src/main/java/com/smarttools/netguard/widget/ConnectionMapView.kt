package com.smarttools.netguard.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.smarttools.netguard.R
import com.smarttools.netguard.util.GeoLookup

class ConnectionMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var userLocation: GeoLookup.LatLon? = null
    private var serverLocation: GeoLookup.LatLon? = null
    private var serverLabel: String? = null
    private var isConnected = false
    /** Transparent overview; motion runs only while the connected map is visible. */
    var overview = false
        set(value) { field = value; updateMotion(); invalidate() }
    private var lineProgress = 0f
    private var pulsePhase = 0f
    private var dashPhase = 0f
    private var lineAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var dashAnimator: ValueAnimator? = null
    private var motionAllowed = true

    private val dp = resources.displayMetrics.density
    private val primaryColor: Int
    private val surfaceColor: Int
    private val isLightTheme: Boolean

    private val accentGreen = Color.parseColor("#4CAF50")

    private val landColor: Int
    private val oceanColor: Int
    private val dotCenterColor: Int

    init {
        val ta = context.obtainStyledAttributes(com.smarttools.netguard.R.styleable.ThemePalette)
        primaryColor = ta.getColor(com.smarttools.netguard.R.styleable.ThemePalette_colorPrimary, Color.parseColor("#7C4DFF"))
        surfaceColor = ta.getColor(com.smarttools.netguard.R.styleable.ThemePalette_colorSurface, Color.BLACK)
        ta.recycle()

        val luminance = (0.299 * Color.red(surfaceColor) +
                0.587 * Color.green(surfaceColor) +
                0.114 * Color.blue(surfaceColor)) / 255.0
        isLightTheme = luminance > 0.5

        if (isLightTheme) {
            oceanColor = blendColors(surfaceColor, Color.BLACK, 0.04f)
            landColor = blendColors(surfaceColor, Color.BLACK, 0.15f)
            dotCenterColor = Color.BLACK
        } else {
            oceanColor = blendColors(surfaceColor, Color.WHITE, 0.05f)
            landColor = blendColors(surfaceColor, Color.WHITE, 0.15f)
            dotCenterColor = Color.WHITE
        }
    }

    // Paints
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = AppTypography.body(context)
        textAlign = Paint.Align.CENTER
    }
    private var measuredLabelSource: String? = null
    private var measuredLabelLimit = -1f
    private var measuredLabel = ""
    private var measuredLabelWidth = 0f
    fun setTerminalTypography(terminal: Boolean) {
        labelPaint.typeface = if (terminal) AppTypography.mono(context) else AppTypography.body(context)
        measuredLabelSource = null
        invalidate()
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridLinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
    }

    // Reusable geometry — preallocated so onDraw() never allocates per frame
    // (lint: DrawAllocation). The view animates at 60fps while connected, so
    // a `new Path()`/`RectF()` per draw would churn the GC.
    private val reuseRect = RectF()
    private val clipPath = Path()
    private val arcPath = Path()
    private val labelRect = RectF()
    private val bmpSrc = Rect()
    private val bmpDst = RectF()
    private val dashIntervals = floatArrayOf(5f * dp, 3f * dp)

    // Optional map image (from drawable resource)
    private var mapBitmap: Bitmap? = null
    private var mapVector: android.graphics.drawable.Drawable? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (isLightTheme) {
            // Grayscale + invert: dark JPG becomes light-themed
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                -0.299f, -0.587f, -0.114f, 0f, 255f,
                -0.299f, -0.587f, -0.114f, 0f, 255f,
                -0.299f, -0.587f, -0.114f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
    }
    private val overviewBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        val ink=if(isLightTheme)30f else 238f
        // Convert the existing dark atlas to translucent land, preserving the
        // shared green/neutral backdrop rather than drawing a black rectangle.
        colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f,0f,0f,0f,ink,
            0f,0f,0f,0f,ink,
            0f,0f,0f,0f,ink,
            1.196f,2.348f,.456f,0f,-56f
        )))
        alpha=160
    }

    // Cached continent paths (fallback when no image)
    private var continentPaths: List<Path>? = null
    private var cachedW = 0f
    private var cachedH = 0f
    private var cachedPad = 0f

    /**
     * Set a map image from drawable resource.
     * Image should be an equirectangular world map (dark themed, transparent or dark ocean).
     */
    fun setMapImage(resId: Int) {
        mapVector = null
        mapBitmap = android.graphics.BitmapFactory.decodeResource(resources, resId)
        invalidate()
    }

    /** Geographic paths on transparent ocean; no JPEG noise or brightness mask. */
    fun setVectorMap(resId: Int) {
        mapBitmap = null
        mapVector = requireNotNull(androidx.appcompat.content.res.AppCompatResources.getDrawable(context, resId)).mutate().apply {
            setTint(if (isLightTheme) Color.rgb(30, 30, 30) else Color.rgb(238, 238, 238))
            alpha = 140
        }
        invalidate()
    }

    fun setLocations(user: GeoLookup.LatLon, server: GeoLookup.LatLon?) {
        userLocation = user
        serverLocation = server
        updateMotion()
        invalidate()
    }

    fun setServerLabel(label: String?) {
        serverLabel = label
        invalidate()
    }

    fun setConnected(connected: Boolean, animate: Boolean = true) {
        val entering = connected && !isConnected
        isConnected = connected
        motionAllowed = animate
        if (entering) lineProgress = 0f
        if (!connected) { lineProgress = 0f; dashPhase = 0f; pulsePhase = 0f }
        updateMotion()
        invalidate()
    }

    private fun stopMotion() {
        lineAnimator?.cancel(); pulseAnimator?.cancel(); dashAnimator?.cancel()
        lineAnimator = null; pulseAnimator = null; dashAnimator = null
    }

    private fun updateMotion() {
        val visible = isAttachedToWindow && windowVisibility == VISIBLE && isShown
        val run = isConnected && visible && userLocation != null && serverLocation != null &&
            motionAllowed && ValueAnimator.areAnimatorsEnabled()
        if (!run) {
            stopMotion()
            lineProgress = if (isConnected) 1f else 0f
            return
        }
        if (dashAnimator != null) return
        if (lineProgress < 1f) {
            lineAnimator = ValueAnimator.ofFloat(lineProgress, 1f).apply {
                duration = 800
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { lineProgress = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        // Arc path starts at the user. A negative phase moves dashes towards the server.
        dashAnimator = ValueAnimator.ofFloat(0f, 8f * dp).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { dashPhase = -(it.animatedValue as Float); invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateMotion() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateMotion()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateMotion()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = if(overview)height.toFloat().coerceAtMost(w*.48f) else height.toFloat()
        if(w<12*dp || h<12*dp)return
        if(overview) {canvas.save();canvas.translate(0f,(height-h)/2f)}
        val pad = 4f * dp
        val mapW = w - pad * 2
        val mapH = h - pad * 2
        val cr = 12f * dp

        // Background + clip to rounded rect
        bgPaint.color = oceanColor
        clipPath.rewind()
        reuseRect.set(0f, 0f, w, h)
        clipPath.addRoundRect(reuseRect, cr, cr, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        if (!overview) canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Map image (if set) takes priority over programmatic drawing
        if (mapVector != null) {
            val drawable = mapVector!!
            canvas.save()
            canvas.translate(pad, pad)
            canvas.scale(mapW / 1024f, mapH / 430f)
            drawable.setBounds(0, 0, 1024, 430)
            drawable.draw(canvas)
            canvas.restore()
        } else if (mapBitmap != null) {
            drawMapBitmap(canvas, pad, mapW, mapH)
        } else {
            // Subtle grid lines
            gridLinePaint.color = blendColors(oceanColor, if (isLightTheme) Color.BLACK else Color.WHITE, 0.04f)
            for (i in 1..5) {
                val x = pad + mapW * i / 6f
                canvas.drawLine(x, pad + 4 * dp, x, h - pad - 4 * dp, gridLinePaint)
            }
            for (i in 1..2) {
                val y = pad + mapH * i / 3f
                canvas.drawLine(pad + 4 * dp, y, w - pad - 4 * dp, y, gridLinePaint)
            }

            // Continent shapes
            buildPathsIfNeeded(w, h, pad, mapW, mapH)
            landPaint.color = landColor
            continentPaths?.forEach { path ->
                canvas.drawPath(path, landPaint)
            }
        }

        val userXY = userLocation?.let { toXY(it, pad, mapW, mapH) }
        val serverXY = serverLocation?.let { toXY(it, pad, mapW, mapH) }

        if (isConnected && lineProgress > 0f && userXY != null && serverXY != null) {
            val (ux, uy) = userXY
            val (sx, sy) = serverXY

            // Arc path — limit height so it stays within the map
            arcPath.rewind()
            val steps = 50
            val maxI = (steps * lineProgress).toInt()
            val midY = (uy + sy) / 2f
            // Arc peak must stay within map bounds (at least pad from top)
            val maxArcUp = midY - pad - 4 * dp
            val desiredArc = minOf(Math.hypot((sx - ux).toDouble(), (sy - uy).toDouble()).toFloat() * 0.25f, mapH * 0.3f)
            val arcHeight = -minOf(desiredArc, maxArcUp.coerceAtLeast(8f * dp))

            for (i in 0..maxI) {
                val t = i.toFloat() / steps
                val px = ux + (sx - ux) * t
                val py = uy + (sy - uy) * t + arcHeight * 4f * t * (1f - t)
                if (i == 0) arcPath.moveTo(px, py) else arcPath.lineTo(px, py)
            }

            // Glow arc
            arcGlowPaint.color = primaryColor
            arcGlowPaint.alpha = (30 * lineProgress).toInt()
            arcGlowPaint.strokeWidth = 8f * dp
            canvas.drawPath(arcPath, arcGlowPaint)

            // Main arc (dashed). DashPathEffect is immutable, so an animated
            // phase needs a fresh instance per frame; the intervals array is
            // preallocated to keep the per-frame allocation to that minimum.
            arcPaint.color = primaryColor
            arcPaint.alpha = (230 * lineProgress).toInt()
            arcPaint.strokeWidth = 1.5f * dp
            arcPaint.pathEffect = DashPathEffect(dashIntervals, dashPhase)
            canvas.drawPath(arcPath, arcPaint)

            // User dot (small)
            dotPaint.color = primaryColor
            dotPaint.alpha = 200
            canvas.drawCircle(ux, uy, 3f * dp, dotPaint)

            // Server dot with pulse
            val pulseR = 8f * dp * (1f + pulsePhase * 1.2f)
            glowPaint.color = accentGreen
            glowPaint.alpha = ((1f - pulsePhase) * 60).toInt()
            canvas.drawCircle(sx, sy, pulseR, glowPaint)
            glowPaint.alpha = ((1f - pulsePhase) * 30).toInt()
            canvas.drawCircle(sx, sy, pulseR * 1.4f, glowPaint)

            dotPaint.color = accentGreen
            dotPaint.alpha = 255
            canvas.drawCircle(sx, sy, 4f * dp, dotPaint)
            dotPaint.color = dotCenterColor
            dotPaint.alpha = 220
            canvas.drawCircle(sx, sy, 1.5f * dp, dotPaint)

            // Server label
            serverLabel?.let { label ->
                labelPaint.textSize = 10f * dp
                labelPaint.color = Color.WHITE
                val limit = (w - 2 * pad - 12 * dp).coerceAtLeast(0f)
                if (measuredLabelSource != label || measuredLabelLimit != limit) {
                    measuredLabelSource = label
                    measuredLabelLimit = limit
                    measuredLabel = android.text.TextUtils.ellipsize(label.replace(LABEL_PREFIX_RE, "").trim(),
                        labelPaint, limit, android.text.TextUtils.TruncateAt.END).toString()
                    measuredLabelWidth = labelPaint.measureText(measuredLabel)
                }
                if (measuredLabel.isEmpty()) return@let
                val textW = measuredLabelWidth
                val minX = pad + textW / 2 + 6 * dp
                val maxX = (w - pad - textW / 2 - 6 * dp).coerceAtLeast(minX)
                val lx = sx.coerceIn(minX, maxX)
                val ly = (sy + 14f * dp).coerceAtMost(h - pad - 6 * dp)

                labelBgPaint.color = Color.BLACK
                labelBgPaint.alpha = 160
                labelRect.set(lx - textW / 2 - 5 * dp, ly - 9 * dp, lx + textW / 2 + 5 * dp, ly + 3 * dp)
                canvas.drawRoundRect(labelRect, 3 * dp, 3 * dp, labelBgPaint)

                labelPaint.alpha = (255 * lineProgress).toInt()
                canvas.drawText(measuredLabel, lx, ly, labelPaint)
            }
        } else {
            // Disconnected — user dot
            userXY?.let { (x, y) ->
                // Glow
                glowPaint.color = primaryColor
                glowPaint.alpha = 40
                canvas.drawCircle(x, y, 6f * dp, glowPaint)
                // Dot
                dotPaint.color = primaryColor
                dotPaint.alpha = 180
                canvas.drawCircle(x, y, 3f * dp, dotPaint)
                // Center
                dotPaint.color = dotCenterColor
                dotPaint.alpha = 140
                canvas.drawCircle(x, y, 1f * dp, dotPaint)
            }
        }
        if (isConnected && serverXY == null) {
            labelPaint.textSize = 11f * dp
            labelPaint.color = primaryColor
            labelPaint.alpha = 180
            val message = android.text.TextUtils.ellipsize(
                resources.getString(R.string.map_server_location_unknown), labelPaint,
                (mapW - 8f * dp).coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END
            ).toString()
            // Align.CENTER already offsets half the text width. Use the view's
            // center directly, so the beginning cannot disappear beyond the edge.
            canvas.drawText(message, w / 2f, pad + mapH - 4f * dp, labelPaint)
        } else if (isConnected && userXY == null && serverXY != null) {
            dotPaint.color = accentGreen; dotPaint.alpha = 255
            canvas.drawCircle(serverXY.first, serverXY.second, 4f * dp, dotPaint)
        }
        canvas.restore()
        if(overview)canvas.restore()
    }

    private fun drawMapBitmap(canvas: Canvas, pad: Float, mapW: Float, mapH: Float) {
        val bmp = mapBitmap ?: return
        bmpSrc.set(0, 0, bmp.width, bmp.height)
        bmpDst.set(pad, pad, pad + mapW, pad + mapH)
        canvas.drawBitmap(bmp, bmpSrc, bmpDst, if(overview)overviewBitmapPaint else bitmapPaint)
    }

    private fun toXY(loc: GeoLookup.LatLon, pad: Float, mapW: Float, mapH: Float): Pair<Float, Float> {
        // Calibrated for the specific map image via pixel analysis
        val latN = if (mapBitmap != null || mapVector != null) MAP_LAT_NORTH else 90.0
        val latS = if (mapBitmap != null || mapVector != null) MAP_LAT_SOUTH else -90.0
        val x = pad + ((loc.lon + 180.0) / 360.0 * mapW).toFloat()
        val y = pad + ((latN - loc.lat) / (latN - latS) * mapH).toFloat()
        return x to y
    }

    companion object {
        // Calibrated via least-squares fit on 6 reference geographic features
        private const val MAP_LAT_NORTH = 91.75
        private const val MAP_LAT_SOUTH = -58.22

        // Strips leading emoji/flag/symbol chars from a server label before
        // drawing. Precompiled — was recompiled on every onDraw().
        private val LABEL_PREFIX_RE = Regex("^[\\p{So}\\p{Cn}\\s]+")
    }

    /** Build continent Path objects from polygon-like shapes. Cached until size changes. */
    private fun buildPathsIfNeeded(w: Float, h: Float, pad: Float, mapW: Float, mapH: Float) {
        if (continentPaths != null && cachedW == w && cachedH == h) return
        cachedW = w; cachedH = h; cachedPad = pad

        val paths = mutableListOf<Path>()

        // Each continent: list of (lat, lon) points forming a closed polygon
        // Simplified but recognizable outlines
        val continents = arrayOf(
            // North America
            floatArrayOf(
                72f,-168f, 72f,-140f, 70f,-100f, 62f,-75f, 55f,-58f,
                48f,-52f, 44f,-65f, 40f,-72f, 30f,-80f, 28f,-82f,
                25f,-80f, 25f,-88f, 20f,-90f, 18f,-88f, 15f,-84f,
                10f,-78f, 8f,-77f, 8f,-82f, 18f,-105f, 22f,-106f,
                30f,-115f, 35f,-120f, 40f,-125f, 48f,-125f, 55f,-132f,
                60f,-140f, 65f,-168f, 72f,-168f
            ),
            // Greenland
            floatArrayOf(
                84f,-20f, 84f,-55f, 78f,-72f, 70f,-55f, 60f,-44f,
                65f,-20f, 72f,-15f, 80f,-18f, 84f,-20f
            ),
            // South America
            floatArrayOf(
                12f,-70f, 10f,-62f, 8f,-58f, 5f,-52f, 0f,-50f,
                -5f,-35f, -10f,-35f, -15f,-38f, -22f,-40f,
                -30f,-48f, -35f,-55f, -40f,-62f, -45f,-68f,
                -55f,-70f, -55f,-75f, -48f,-75f, -40f,-73f,
                -30f,-72f, -20f,-70f, -15f,-76f, -5f,-80f,
                0f,-78f, 5f,-77f, 8f,-72f, 12f,-70f
            ),
            // Europe
            floatArrayOf(
                72f,28f, 70f,30f, 65f,30f, 62f,32f, 58f,28f,
                56f,22f, 55f,14f, 54f,10f, 56f,8f, 58f,6f,
                56f,-5f, 52f,-10f, 48f,-8f, 44f,-8f, 38f,-8f,
                36f,-6f, 36f,0f, 38f,2f, 42f,3f, 44f,8f,
                42f,14f, 40f,20f, 38f,24f, 40f,28f, 42f,30f,
                46f,30f, 48f,22f, 50f,14f, 52f,8f, 54f,12f,
                56f,18f, 58f,24f, 62f,26f, 66f,26f, 70f,28f, 72f,28f
            ),
            // Africa
            floatArrayOf(
                36f,-5f, 36f,10f, 32f,32f, 28f,34f, 22f,36f,
                15f,42f, 12f,50f, 8f,48f, 2f,42f, -2f,42f,
                -10f,40f, -15f,38f, -22f,35f, -28f,32f,
                -34f,28f, -35f,20f, -30f,18f, -25f,15f,
                -18f,12f, -8f,10f, -4f,10f, 2f,10f,
                5f,0f, 5f,-4f, 10f,-10f, 15f,-17f,
                20f,-17f, 26f,-15f, 30f,-10f, 36f,-5f
            ),
            // Russia/North Asia
            floatArrayOf(
                72f,28f, 72f,40f, 76f,60f, 78f,100f, 76f,140f,
                72f,178f, 66f,178f, 62f,160f, 58f,140f, 52f,130f,
                48f,135f, 46f,132f, 44f,130f, 42f,120f, 46f,88f,
                42f,75f, 44f,50f, 42f,42f, 42f,30f, 46f,30f,
                48f,32f, 52f,38f, 55f,38f, 58f,42f, 62f,38f,
                66f,32f, 72f,28f
            ),
            // South/Southeast Asia + India
            floatArrayOf(
                35f,72f, 28f,68f, 22f,70f, 18f,72f, 12f,75f,
                8f,77f, 8f,80f, 15f,80f, 22f,88f, 22f,97f,
                18f,98f, 12f,100f, 8f,100f, 2f,104f, -2f,106f,
                -8f,110f, -8f,115f, -4f,118f, 0f,118f, 4f,115f,
                8f,108f, 15f,108f, 22f,108f, 28f,100f, 30f,90f,
                35f,80f, 35f,72f
            ),
            // China/East Asia
            floatArrayOf(
                48f,88f, 46f,92f, 42f,80f, 38f,76f, 35f,80f,
                35f,98f, 28f,100f, 22f,108f, 22f,115f, 25f,120f,
                30f,122f, 35f,120f, 38f,118f, 40f,122f, 42f,128f,
                46f,132f, 48f,135f, 52f,130f, 50f,118f, 48f,88f
            ),
            // Japan
            floatArrayOf(
                45f,140f, 42f,140f, 38f,136f, 35f,133f, 33f,131f,
                34f,130f, 36f,132f, 38f,134f, 40f,138f, 42f,142f,
                44f,145f, 45f,146f, 45f,140f
            ),
            // Australia
            floatArrayOf(
                -12f,132f, -14f,127f, -16f,123f, -20f,118f, -24f,114f,
                -30f,115f, -34f,116f, -35f,118f, -38f,144f, -36f,150f,
                -32f,152f, -28f,154f, -24f,153f, -20f,148f, -16f,146f,
                -14f,142f, -12f,136f, -12f,132f
            ),
            // Indonesia (Borneo/Sumatra/Java simplified)
            floatArrayOf(
                6f,96f, 2f,98f, -2f,100f, -6f,105f, -8f,110f,
                -8f,118f, -6f,120f, -2f,118f, 0f,114f, 2f,110f,
                4f,104f, 6f,100f, 6f,96f
            ),
            // New Zealand
            floatArrayOf(
                -35f,173f, -38f,175f, -42f,172f, -46f,167f,
                -44f,168f, -40f,174f, -37f,176f, -35f,173f
            ),
            // Middle East (Arabian Peninsula)
            floatArrayOf(
                32f,34f, 28f,34f, 22f,36f, 15f,42f, 12f,44f,
                14f,52f, 22f,58f, 26f,56f, 28f,50f, 30f,48f,
                32f,44f, 32f,34f
            ),
            // UK/Ireland
            floatArrayOf(
                58f,-6f, 56f,-6f, 52f,-5f, 50f,-5f, 51f,1f,
                53f,0f, 55f,-1f, 58f,-3f, 58f,-6f
            ),
        )

        for (pts in continents) {
            val path = Path()
            var i = 0
            while (i < pts.size - 1) {
                val lat = pts[i]; val lon = pts[i + 1]
                val x = pad + ((lon + 180f) / 360f * mapW)
                val y = pad + ((90f - lat) / 180f * mapH)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                i += 2
            }
            path.close()
            paths.add(path)
        }
        continentPaths = paths
    }

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.rgb(
            (Color.red(c1) * inv + Color.red(c2) * ratio).toInt(),
            (Color.green(c1) * inv + Color.green(c2) * ratio).toInt(),
            (Color.blue(c1) * inv + Color.blue(c2) * ratio).toInt()
        )
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }
}
