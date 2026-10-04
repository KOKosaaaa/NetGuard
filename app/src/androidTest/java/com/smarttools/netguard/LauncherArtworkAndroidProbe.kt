package com.smarttools.netguard

import android.app.Instrumentation
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/** Evidence from installed Android resources, including the platform's actual adaptive mask. */
internal class LauncherArtworkAndroidProbe(private val test: Instrumentation) {
    private fun render(drawable: Drawable, size: Int): Bitmap {
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(result))
        return result
    }

    private fun masked(icon: AdaptiveIconDrawable, roundedSquare: Boolean): Bitmap {
        val size = 128
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val path = Path()
        if (roundedSquare) path.addRoundRect(RectF(0f, 0f, 128f, 128f), 28f, 28f, Path.Direction.CW)
        else path.addCircle(64f, 64f, 64f, Path.Direction.CW)
        canvas.clipPath(path)
        // Adaptive artwork's centered 72 units are displayed at rest; 18 units
        // on each side provide the standard extra inset for motion effects.
        val destination = RectF(-32f, -32f, 160f, 160f)
        for (layer in listOf(icon.background, icon.foreground)) {
            val bitmap = render(layer, 108)
            canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            bitmap.recycle()
        }
        return output
    }

    fun run() {
        val context = test.targetContext
        val names = listOf("Default", "Light", "Oled", "Ocean", "Fsociety", "Dynamic")
        val sheet = Bitmap.createBitmap(1080, 1100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(37, 39, 44))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f }
        val rows = JSONArray()
        for ((index, name) in names.withIndex()) {
            val component = ComponentName(context.packageName, "${context.packageName}.launcher.$name")
            @Suppress("DEPRECATION")
            val info = context.packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
            val icon = info.loadIcon(context.packageManager)
            check(icon is AdaptiveIconDrawable) { "$name is not an adaptive icon on this device" }
            val x = index * 180f
            canvas.drawText(name, x + 20, 25f, text)
            val large = render(icon, 128)
            canvas.drawBitmap(large, x + 26, 45f, null)
            large.recycle()
            canvas.drawText("Installed mask / 128px", x + 8, 195f, text)
            val small = render(icon, 48)
            canvas.drawBitmap(small, x + 66, 213f, null)
            small.recycle()
            canvas.drawText("48px", x + 68, 287f, text)

            // The foreground's 108-unit viewport must keep visible artwork in the
            // centered 66-unit safe circle (one pixel allowance for antialiasing).
            val foreground = render(icon.foreground, 108)
            var maxRadius = 0.0
            var outside = 0
            var visible = 0
            val palette = mutableMapOf<Int, Int>()
            for (y in 0 until 108) for (xx in 0 until 108) {
                val pixel = foreground.getPixel(xx, y)
                if (Color.alpha(pixel) == 255) palette[pixel] = (palette[pixel] ?: 0) + 1
                if (Color.alpha(pixel) > 16) {
                    visible++
                    val radius = hypot(xx + 0.5 - 54, y + 0.5 - 54)
                    maxRadius = maxOf(maxRadius, radius)
                    if (radius > 34) outside++
                }
            }
            canvas.drawBitmap(foreground, x + 36, 306f, null)
            foreground.recycle()
            canvas.drawText("Raw foreground / 108", x + 6, 435f, text)
            check(visible > 100) { "$name foreground is empty" }
            val row = JSONObject().put("alias", name).put("foregroundVisiblePixels", visible)
                .put("foregroundMaxRadius", maxRadius).put("pixelsOutsideSafeCircleWithAa", outside)
            val solidColors = JSONArray()
            palette.entries.sortedByDescending { it.value }.take(16).forEach {
                solidColors.put(JSONObject().put("argb", String.format("#%08X", it.key)).put("pixels", it.value))
            }
            row.put("resolvedForegroundPalette", solidColors)
            val background = render(icon.background, 1)
            row.put("resolvedBackgroundArgb", String.format("#%08X", background.getPixel(0, 0)))
            background.recycle()
            val colorResources = JSONObject()
            val prefix = if (name == "Default") "ic_launcher" else "ic_launcher_${name.lowercase()}"
            for (suffix in listOf("background", "foreground", "accent")) {
                val resourceName = "${prefix}_$suffix"
                val resourceId = context.resources.getIdentifier(resourceName, "color", context.packageName)
                if (resourceId != 0) colorResources.put(resourceName, String.format("#%08X", context.getColor(resourceId)))
            }
            row.put("resolvedColorResources", colorResources)
            if (Build.VERSION.SDK_INT >= 33) {
                val mono = icon.monochrome ?: error("$name has no monochrome resource on API33+")
                val bitmap = render(mono, 108)
                var opaque = 0
                for (y in 0 until 108) for (xx in 0 until 108) if (Color.alpha(bitmap.getPixel(xx, y)) > 16) opaque++
                check(opaque in 100..9000) { "$name monochrome is blank or an opaque background" }
                bitmap.recycle()
                val foregroundCopy = mono.constantState!!.newDrawable(context.resources).mutate().apply { setTint(Color.WHITE) }
                val themed = AdaptiveIconDrawable(ColorDrawable(Color.rgb(55, 58, 65)), foregroundCopy)
                val largeMono = render(themed, 128)
                canvas.drawBitmap(largeMono, x + 26, 459f, null)
                largeMono.recycle()
                canvas.drawText("Mono / masked 128", x + 13, 608f, text)
                val smallMono = render(themed, 48)
                canvas.drawBitmap(smallMono, x + 66, 627f, null)
                smallMono.recycle()
                canvas.drawText("Mono / masked 48", x + 19, 705f, text)
                row.put("monochromeVisiblePixels", opaque)
            }
            val circle = masked(icon, false)
            canvas.drawBitmap(circle, x + 26, 731f, null)
            circle.recycle()
            canvas.drawText("Circle mask / 128", x + 19, 881f, text)
            val roundedSquare = masked(icon, true)
            canvas.drawBitmap(roundedSquare, x + 26, 905f, null)
            roundedSquare.recycle()
            canvas.drawText("Rounded-square / 128", x + 5, 1061f, text)
            rows.put(row)
        }
        val directory = context.getExternalFilesDir(null)!!
        File(directory, "launcher-artwork-installed.png").outputStream().use {
            check(sheet.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        sheet.recycle()
        File(directory, "launcher-artwork-installed.json").writeText(JSONObject()
            .put("api", Build.VERSION.SDK_INT).put("densityDpi", context.resources.displayMetrics.densityDpi)
            .put("aliases", rows).toString(2))
        // Preserve visual/bounds evidence even if an asset exceeds its safe zone.
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            check(row.getInt("pixelsOutsideSafeCircleWithAa") == 0) {
                "${row.getString("alias")} artwork exceeds adaptive safe circle: ${row.getInt("pixelsOutsideSafeCircleWithAa")} pixels"
            }
        }
    }
}
