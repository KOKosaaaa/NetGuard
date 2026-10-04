package com.smarttools.netguard.widget

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.smarttools.netguard.App
import com.smarttools.netguard.R
import com.smarttools.netguard.model.ThemeMode

/** Shared native material: no screen capture, WebView or continuous rendering loop. */
class LiquidBackdrop @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    var connectionLight = 0f; private set
    val mode = (context.applicationContext as App).loadSettings().themeMode
    private fun color(attr: Int) = android.util.TypedValue().also { context.theme.resolveAttribute(attr, it, true) }.data
    val light = ColorUtils.calculateLuminance(color(com.google.android.material.R.attr.colorSurface)) > .5
    val terminal = mode == ThemeMode.FSOCIETY
    val foreground = if (terminal) Color.rgb(220,249,228) else if (light) Color.rgb(30,27,39) else Color.rgb(238,245,241)
    val muted = if (terminal) Color.rgb(143,179,155) else if (light) Color.rgb(90,86,103) else Color.rgb(175,188,181)
    val accent get() = if (terminal) Color.rgb(70,255,128) else if (connectionLight > .5) {
        if (light) Color.rgb(0,105,60) else Color.rgb(108,255,176)
    } else color(com.google.android.material.R.attr.colorPrimary)
    val base: Int = when(mode) {
        ThemeMode.FSOCIETY -> Color.rgb(9,12,11)
        ThemeMode.DARK -> Color.rgb(15,12,23)
        ThemeMode.LIGHT -> Color.rgb(242,238,248)
        ThemeMode.OLED -> Color.BLACK
        ThemeMode.OCEAN -> Color.rgb(4,14,24)
        ThemeMode.DYNAMIC -> color(android.R.attr.colorBackground)
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun setConnected(connected: Boolean, animate: Boolean = true) {
        val target = if (connected) 1f else 0f
        if (connectionLight == target && animator == null) return
        animator?.cancel()
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) { connectionLight=target; invalidate(); return }
        animator=ValueAnimator.ofFloat(connectionLight,target).apply {
            duration=450
            addUpdateListener { connectionLight=it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() { animator?.cancel(); animator=null; super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(base)
        // Texture uses low-alpha paint; restore full opacity before shader compositing.
        paint.color=Color.WHITE;paint.shader=null
        if (connectionLight > 0) {
        val strength=if(mode==ThemeMode.OLED) .55f else if(terminal) .72f else 1f
        val green=ColorUtils.setAlphaComponent(Color.rgb(15,208,101),(110*connectionLight*strength).toInt())
        paint.shader=RadialGradient(width*1.12f,height*.25f,width*1.22f,green,Color.TRANSPARENT,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
        paint.shader=RadialGradient(-width*.18f,height*.98f,width*.95f,
            ColorUtils.setAlphaComponent(green,(Color.alpha(green)*.65f).toInt()),Color.TRANSPARENT,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
        paint.shader=null
        }
        if (terminal) {
            // Static phosphor texture, behind controls. No timer, flicker or render loop.
            val d=resources.displayMetrics.density
            paint.shader=null;paint.strokeWidth=d*.5f
            paint.color=Color.argb(5,70,255,128)
            var y=0f
            while(y<height) { canvas.drawLine(0f,y,width.toFloat(),y,paint);y+=4*d }
            paint.color=Color.argb(7,70,255,128)
            var x=0f
            while(x<width) { canvas.drawLine(x,0f,x,height.toFloat(),paint);x+=48*d }
        }
    }
}

/** Translucent tint with optical edge and a shallow highlight, keeping text sharp. */
class GlassDrawable(private val light: Boolean, private val active: Boolean, private val radius: Float,
                    private val emphasis: Boolean = false, private val terminal: Boolean = false) : Drawable() {
    // AppCompat mutates RippleDrawable when applying background tint. Its layers
    // need independent children; sharing this stateful drawable steals callbacks.
    private class GlassState(private val light: Boolean, private val active: Boolean,
        private val radius: Float, private val emphasis: Boolean, private val terminal: Boolean) : ConstantState() {
        override fun newDrawable(): Drawable = GlassDrawable(light, active, radius, emphasis, terminal)
        override fun getChangingConfigurations() = 0
    }
    override fun getConstantState(): ConstantState = GlassState(light, active, radius, emphasis, terminal)
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var enabled=true
    private var drawableAlpha=255
    override fun isStateful()=true
    override fun onStateChange(state: IntArray): Boolean {
        val next=state.contains(android.R.attr.state_enabled)
        if(next==enabled)return false
        enabled=next;invalidateSelf();return true
    }
    private fun alpha(color: Int, value: Int)=ColorUtils.setAlphaComponent(color,
        (value * drawableAlpha / 255f * if(enabled)1f else .45f).toInt().coerceIn(0,255))
    override fun draw(c: Canvas) {
        val r=RectF(bounds).apply { inset(1f,1f) }
        val tint=if(terminal) Color.rgb(79,221,128) else if(active) Color.rgb(93,233,156) else if(light) Color.WHITE else Color.rgb(193,200,209)
        val a=if(light) 110 else if(emphasis) 62 else 23
        p.shader=LinearGradient(r.left,r.top,r.right,r.bottom,
            intArrayOf(alpha(tint,a+18),alpha(tint,a/2)),null,Shader.TileMode.CLAMP)
        p.style=Paint.Style.FILL;c.drawRoundRect(r,radius,radius,p)
        p.shader=LinearGradient(r.left,r.top,r.right,r.bottom,
            intArrayOf(alpha(Color.WHITE,if(light)220 else if(emphasis)155 else 75),
                alpha(tint,24),alpha(tint,90)),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        p.style=Paint.Style.STROKE;p.strokeWidth=if(emphasis)2f else 1.2f;c.drawRoundRect(r,radius,radius,p)
        p.shader=null;p.style=Paint.Style.FILL
    }
    override fun setAlpha(alpha: Int) {drawableAlpha=alpha;invalidateSelf()}
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

object LiquidGlass {
    fun decorate(root: View, backdrop: LiquidBackdrop, connected: Boolean) {
        val d=root.resources.displayMetrics.density
        when(root) {
            is MaterialCardView -> {
                val unframed=root.id==R.id.connection_card || root.id==R.id.history_card
                root.setCardBackgroundColor(Color.TRANSPARENT);root.cardElevation=0f
                root.radius=if(unframed)0f else 24*d;root.strokeWidth=if(root.isSelected)2 else 0
                // Draw below the children. A card foreground used to wash out labels and
                // add a second optical rim over already decorated controls inside it.
                root.foreground=null
                val glass=GlassDrawable(backdrop.light,connected,24*d,root.isSelected,backdrop.terminal)
                root.background=if(unframed)null else if(root.isClickable)RippleDrawable(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(backdrop.accent,40)),glass,
                    GradientDrawable().apply {setColor(Color.WHITE);cornerRadius=24*d}) else glass
            }
            is BottomNavigationView -> {
                root.background=GlassDrawable(backdrop.light,connected,32*d,terminal=backdrop.terminal)
                root.elevation=0f;root.itemRippleColor=ColorStateList.valueOf(ColorUtils.setAlphaComponent(backdrop.accent,35))
                root.itemActiveIndicatorColor=ColorStateList.valueOf(ColorUtils.setAlphaComponent(backdrop.accent,35))
                val colors=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(backdrop.accent,backdrop.muted))
                root.itemTextColor=colors;root.itemIconTintList=colors
                if(backdrop.terminal) {
                    fun terminalLabels(v:View) {
                        if(v is TextView)v.typeface=AppTypography.mono(v.context)
                        if(v is ViewGroup)for(i in 0 until v.childCount)terminalLabels(v.getChildAt(i))
                    }
                    terminalLabels(root)
                }
                return // Navigation owns checked/unchecked label state; never flatten its children.
            }
            is MaterialToolbar -> {
                root.setBackgroundColor(Color.TRANSPARENT);root.setTitleTextColor(if(backdrop.terminal)backdrop.accent else backdrop.foreground)
                root.setTitleTextAppearance(root.context,R.style.TextAppearance_NetGuard_GlassTitle)
            }
            is MaterialButton -> {
                val quiet=root.id==R.id.btn_auto_select || root.id==R.id.btn_speed_test || root.id==R.id.btn_details || root.id==R.id.btn_cancel_speed
                // One background and one rim, in the same bounds. Material's inset
                // background + a full-size foreground produced the doubled pills.
                root.foreground=null
                root.stateListAnimator=null;root.elevation=0f
                root.strokeWidth=0
                root.backgroundTintList=null
                val glass=if(quiet)null else GlassDrawable(backdrop.light,connected,26*d,root.id==R.id.btn_connect,backdrop.terminal)
                val mask=GradientDrawable().apply { setColor(Color.WHITE);cornerRadius=26*d }
                root.background=RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(backdrop.accent,40)),glass,mask)
                val labelColors=ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),
                    intArrayOf(backdrop.muted,backdrop.foreground))
                root.setTextColor(labelColors);root.iconTint=labelColors
                root.isAllCaps=false;root.letterSpacing=0f
                root.typeface=if(backdrop.terminal)AppTypography.mono(root.context) else AppTypography.heading(root.context)
                if(quiet)root.setPadding(root.paddingLeft,(6*d).toInt(),root.paddingRight,(6*d).toInt())
            }
            is MaterialSwitch -> {
                root.setTextColor(backdrop.foreground)
                root.typeface=if(backdrop.terminal)AppTypography.mono(root.context) else AppTypography.heading(root.context)
                root.trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(ColorUtils.setAlphaComponent(backdrop.accent,115),ColorUtils.setAlphaComponent(backdrop.muted,45)))
                root.thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(backdrop.accent,backdrop.muted))
            }
            is TextView -> {
                // Preserve explicit error/result/status colors; remove terminal typography from prose.
                if(root.id!=R.id.tv_status && root.id!=R.id.tv_speed_error && root.id!=R.id.tv_created_by && root.id!=R.id.tv_ping && root.id!=R.id.tv_protocol)
                    root.setTextColor(if(backdrop.light && root.id==R.id.tv_profile_protocol)Color.BLACK else if(root.textSize/d<14)backdrop.muted else backdrop.foreground)
                val mono=root.id==R.id.tv_timer || root.id==R.id.tv_fsoc_prompt || root.id==R.id.tv_down_speed || root.id==R.id.tv_up_speed || root.id==R.id.tv_stats_today ||
                    (backdrop.terminal && (root.id==R.id.tv_fsoc_header || root.id==R.id.tv_name || root.id==R.id.tv_address || root.id==R.id.tv_sub_name || root.id==R.id.tv_ping || root.id==R.id.tv_protocol || root.id==R.id.tv_profile_protocol || root.parent is MaterialToolbar))
                val heading=(root.getTag(R.id.glass_heading) as? Boolean) ?: (root.id==R.id.tv_status || root.id==R.id.tv_profile_name || AppTypography.isHeading(root.typeface)).also {
                    root.setTag(R.id.glass_heading,it)
                }
                root.typeface=if(mono || backdrop.terminal && heading)AppTypography.mono(root.context) else if(heading)AppTypography.heading(root.context) else AppTypography.body(root.context)
                if(backdrop.terminal && (root.id==R.id.tv_fsoc_header || root.id==R.id.tv_fsoc_prompt || root.parent is MaterialToolbar))root.setTextColor(backdrop.accent)
            }
        }
        if(root.id==R.id.layout_stats) root.background=GlassDrawable(backdrop.light,connected,22*d,terminal=backdrop.terminal)
        if(root is RecyclerView && root.getTag(R.id.glass_decorated)==null) {
            root.setTag(R.id.glass_decorated,true)
            root.addOnChildAttachStateChangeListener(object:RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(v:View) {
                    val act=v.context as? com.smarttools.netguard.MainActivity ?: return
                    act.decorateGlass(v)
                }
                override fun onChildViewDetachedFromWindow(v:View) { }
            })
        }
        if(root is ViewGroup) for(i in 0 until root.childCount)decorate(root.getChildAt(i),backdrop,connected)
    }
}
