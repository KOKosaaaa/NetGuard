package com.smarttools.netguard

import android.app.Activity
import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import com.smarttools.netguard.util.GeoLookup
import com.smarttools.netguard.widget.ConnectionMapView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Visual capture only: real production View, localized app resources, no network or saved state. */
internal class MapLabelCapture(private val test: Instrumentation) {
    fun run() {
        val result=Bundle()
        var code=Activity.RESULT_CANCELED
        try {
            test.waitForIdleSync()
            val rows=JSONArray()
            var error:Throwable?=null
            test.runOnMainSync {
                try {
                    for (terminal in listOf(false,true)) for (language in listOf("ru","en")) {
                        val config=Configuration(test.targetContext.resources.configuration)
                        config.setLocale(Locale.forLanguageTag(language))
                        val themeName=if(terminal) "fsociety" else "dark"
                        val context=ContextThemeWrapper(test.targetContext.createConfigurationContext(config),if(terminal) R.style.Theme_NetGuard_Fsociety else R.style.Theme_NetGuard)
                        val density=context.resources.displayMetrics.density
                        for (widthDp in listOf(120,240,320)) for (overview in listOf(false,true)) {
                            val w=(widthDp*density).toInt()
                            val h=(maxOf(120f,widthDp*.55f)*density).toInt()
                            val view=ConnectionMapView(context).apply {
                                this.overview=overview
                                setTerminalTypography(terminal)
                                setVectorMap(R.drawable.world_map_clean)
                                setLocations(GeoLookup.LatLon(55.8,37.6),null)
                                setConnected(true,false)
                            }
                            view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
                            view.layout(0,0,w,h)
                            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                            try {
                                val canvas=Canvas(bitmap)
                                canvas.drawColor(if(terminal) Color.rgb(8,20,15) else Color.rgb(18,18,24))
                                view.draw(canvas)
                                val name="map-label-$themeName-$language-${widthDp}dp-${if(overview) "overview" else "regular"}.png"
                                File(test.targetContext.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                                rows.put(JSONObject().put("file",name).put("theme",themeName).put("font",if(terminal) "JetBrainsMono" else "ManropeRegular").put("language",context.resources.configuration.locales[0].language).put("widthDp",widthDp).put("widthPx",w).put("heightPx",h).put("overview",overview))
                            } finally { bitmap.recycle() }
                        }
                    }
                } catch(t:Throwable) { error=t }
            }
            error?.let { throw it }
            File(test.targetContext.getExternalFilesDir(null),"map-label-capture.json").writeText(JSONObject().put("scope","Offscreen native ConnectionMapView Canvas render; DARK/FSOCIETY theme with same typography setter as Home; no network/VPN or global locale mutation").put("captures",rows).toString(2))
            result.putString("stream","CAPTURE COMPLETE: ${rows.length()} actual native View PNGs; visual review required, no automated clipping verdict\n")
            code=Activity.RESULT_OK
        } catch(t:Throwable) { result.putString("stream","CAPTURE FAILED: ${t.stackTraceToString()}\n") }
        test.finish(code,result)
    }
}
