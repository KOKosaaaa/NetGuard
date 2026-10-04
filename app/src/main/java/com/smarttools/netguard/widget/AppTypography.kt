package com.smarttools.netguard.widget

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.smarttools.netguard.R

/** Bundled Cyrillic/Latin fonts: available offline, consistent across device vendors. */
object AppTypography {
    private val fonts = java.util.concurrent.ConcurrentHashMap<Int, Typeface>()
    private fun font(context: Context, id: Int): Typeface = fonts.getOrPut(id) {
        requireNotNull(ResourcesCompat.getFont(context.applicationContext, id))
    }
    fun body(context: Context) = font(context, R.font.manrope)
    fun heading(context: Context) = font(context, R.font.manrope_semibold)
    fun mono(context: Context) = font(context, R.font.jetbrains_mono)
    fun isHeading(face: Typeface?) = face?.isBold == true ||
        (android.os.Build.VERSION.SDK_INT >= 28 && (face?.weight ?: 400) >= 600)
}
