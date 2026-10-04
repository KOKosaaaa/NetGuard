package com.smarttools.netguard

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.smarttools.netguard.service.*
import com.smarttools.netguard.ui.logs.LogFragment
import java.io.File

/** Actual typed diagnostic sheet/buttons; synthetic evidence only, no network initiated. */
internal class RouteDiagnosticsAndroidProbe(private val test: Instrumentation) {
    private fun onUi(action:()->Unit) {
        var failure:Throwable?=null
        test.runOnMainSync {try{action()}catch(t:Throwable){failure=t}}
        failure?.let{throw it}
    }
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    private fun text(v:View)=views(v).filterIsInstance<TextView>().filter{it.isShown}.joinToString("\n"){it.text}
    private fun waitUi(message:String,predicate:()->Boolean) {
        val until=System.nanoTime()+8_000_000_000L
        while(true){var ok=false;onUi{ok=predicate()};if(ok)return;check(System.nanoTime()<until){message};Thread.sleep(50)}
    }
    private fun screenshot(name:String) {
        val deadline=System.nanoTime()+8_000_000_000L
        var activePackage=test.uiAutomation.rootInActiveWindow?.packageName?.toString()
        while(activePackage!=test.targetContext.packageName && System.nanoTime()<deadline) {
            Thread.sleep(50)
            activePackage=test.uiAutomation.rootInActiveWindow?.packageName?.toString()
        }
        check(activePackage==test.targetContext.packageName){"Non-app overlay covers diagnostics: active=$activePackage"}
        val b=checkNotNull(test.uiAutomation.takeScreenshot())
        try{File(test.targetContext.getExternalFilesDir(null),name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}
    }
    private fun full(v:View) {
        val r=android.graphics.Rect()
        check(v.isShown && v.width>0 && v.height>0 && v.getGlobalVisibleRect(r) && r.width()>=v.width-1 && r.height()>=v.height-1){
            val location=IntArray(2);v.getLocationOnScreen(location)
            "Diagnostic control clipped: ${v.javaClass.simpleName} ${v.width}x${v.height} rect=$r screen=${location.toList()} root=${v.rootView.width}x${v.rootView.height} parent=${(v.parent as? View)?.let { "${it.width}x${it.height}" }}"
        }
        if(v is TextView){val l=checkNotNull(v.layout)
            check(l.height<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom+1){"Action text vertically clipped"}
            repeat(l.lineCount){check(l.getLineMax(it)<=v.width-v.compoundPaddingLeft-v.compoundPaddingRight+1){"Action text horizontally clipped"}}
        }
    }
    fun run(language:String="ru") {
        var main:MainActivity?=null
        val report=StringBuilder();val app=test.targetContext.applicationContext as App;val diag=RouteDiagnostics.shared
        var code=Activity.RESULT_CANCELED;var saved:Map<String,*>?=null
        val manager=if(android.os.Build.VERSION.SDK_INT>=33)test.targetContext.getSystemService(android.app.LocaleManager::class.java)else null
        val oldLocale=manager?.applicationLocales
        val oldCompat=androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        try {
            test.waitForIdleSync()
            waitUi("Application not initialized"){runCatching{app.database;true}.getOrDefault(false)}
            check(TunnelVpnService.connectionState.value is com.smarttools.netguard.model.ConnectionState.Disconnected){"Owned idle app required"}
            saved=app.getPreferences().all.toMap()
            app.getPreferences().edit().putBoolean("onboarding_done",true).putBoolean("expert_mode",true).commit()
            onUi{if(manager!=null)manager.applicationLocales=LocaleList.forLanguageTags(language)
                else androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(language))}
            val token=diag.network(diag.begin(true))
            val sites=(0 until 36).map{SiteOrigin("diagnostic-fixture-$it.invalid",443,true)}
            sites.forEach{site->diag.decision(token,SiteRouteRecord(site,site.host,SitePath.VPN,System.currentTimeMillis(),
                SiteMeasurement(outcome="TIMEOUT"),SiteMeasurement(2,300,"BODY","VERIFIED",98304,200),
                SitePath.TLS_RECORD_SNI,SiteMeasurement(2,120,"BODY","VERIFIED_TWICE",98304,200)),false)}
            main=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity=main!!;test.waitForIdleSync()
            check(activity.resources.configuration.locales[0].language==language)
            onUi{(activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController.navigate(R.id.nav_logs)}
            test.waitForIdleSync()
            lateinit var fragment:LogFragment
            onUi{
                fragment=(activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).childFragmentManager.primaryNavigationFragment as LogFragment
                check(fragment.requireView().findViewById<View>(R.id.btn_route_diagnostics).performClick())
            }
            test.waitForIdleSync()
            val dialog=LogFragment::class.java.getDeclaredField("diagnosticDialog").apply{isAccessible=true}.get(fragment) as Dialog
            val decor=dialog.window!!.decorView
            waitUi("Diagnostic sheet did not become focused and fully expanded") {
                dialog.isShowing && decor.hasWindowFocus() && !decor.isLayoutRequested &&
                    (dialog as com.google.android.material.bottomsheet.BottomSheetDialog).behavior.state ==
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
            val list=views(decor).filterIsInstance<RecyclerView>().single()
            val layout=list.layoutManager as LinearLayoutManager
            val adapter=list.adapter as ListAdapter<*,*>
            fun rows()=adapter.currentList.filterIsInstance<RouteDiagnostics.UiRow>()
            val buttons=views(decor).filterIsInstance<MaterialButton>();check(buttons.size==3)
            val copy=buttons.single{it.text.toString()==activity.getString(android.R.string.copy)}
            val save=buttons.single{it.text.toString()==activity.getString(R.string.save)}
            val close=buttons.single{it.text.toString()==activity.getString(R.string.close)}
            waitUi("Typed rows not rendered"){list.childCount>0 && rows().size==36}
            onUi{
                check(text(decor).contains(if(language=="ru")"Трафик приложения ещё не наблюдался" else "No application traffic observed"))
                check(!text(decor).contains("98304 B")){"Collapsed card exposed raw details"}
                check(list.getChildAt(0).performClick())
            }
            waitUi("Card did not expand probe details"){text(decor).contains("BODY/VERIFIED_TWICE") && text(decor).contains("98304 B")}
            onUi{buttons.forEach(::full);full(list);check(list.height>activity.resources.displayMetrics.density*48){
                "No usable report viewport: list=${list.width}x${list.height}, density=${activity.resources.displayMetrics.density}, decor=${decor.width}x${decor.height}, ancestors="+
                    generateSequence(list.parent as? View){it.parent as? View}.joinToString { "${it.javaClass.simpleName}:${it.width}x${it.height}@${it.top}" }
            }}
            screenshot("route-diagnostics-$language-expanded.png")
            report.append("PASS $language typed cards: verified probes separate from absent actual traffic; details expand; actions visible\n")
            val flow=diag.flow(token,sites.last(),"TLS_RECORD_SNI","FIRST_OR_CACHE");flow.progress(1234,5678)
            repeat(10000){LogBuffer.add(LogBuffer.LogLevel.INFO,"WB fixture line $it")}
            waitUi("Live counters not refreshed"){rows().first().traffic.contains("↑") && text(decor).contains(if(language=="ru")"передача в обе стороны" else "bidirectional socket traffic")}
            onUi{layout.scrollToPositionWithOffset(12,0)};test.waitForIdleSync()
            var position=0;var offset=0;var key=""
            onUi{position=layout.findFirstVisibleItemPosition();offset=layout.findViewByPosition(position)!!.top;key=rows()[position].key;check(position>=10)}
            fun anchor(){check(layout.findFirstVisibleItemPosition()==position && layout.findViewByPosition(position)?.top==offset && rows()[position].key==key){"Live diff reset report position"}}
            var up=1234L;var down=5678L
            repeat(3){tick->
                flow.progress(111,222);up+=111;down+=222
                waitUi("Diff update $tick absent"){
                    anchor();val traffic=rows().first().traffic
                    traffic.contains(com.smarttools.netguard.util.TrafficFormatter.formatBytes(up)) && traffic.contains(com.smarttools.netguard.util.TrafficFormatter.formatBytes(down))
                }
                Thread.sleep(250);onUi{anchor();buttons.forEach(::full)}
            }
            screenshot("route-diagnostics-$language.png")
            report.append("PASS three timed diff refreshes retain visible stable key/pixel offset under10000 log lines\n")
            flow.close(false)
            onUi{
                LogBuffer.add(LogBuffer.LogLevel.INFO,"COPY_EXPORT_FULL_LOG_MARKER")
                check(copy.performClick());check(dialog.isShowing);anchor()
                val clipboard=test.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val value=checkNotNull(clipboard.primaryClip).getItemAt(0).coerceToText(test.targetContext).toString()
                check(value.contains("TLS_RECORD_SNI:confirmed: BODY/VERIFIED_TWICE, HTTP=200, 98304 B"))
                check(value.contains("↑$up B ↓$down B") && value.contains("COPY_EXPORT_FULL_LOG_MARKER"))
                check(!value.contains("diagnostic-fixture-") && value.contains("site-"))
            }
            val dir=File(test.targetContext.getExternalFilesDir(null),"logs")
            val previous=dir.listFiles()?.associate{it.name to(it.lastModified() to it.length())}.orEmpty();val start=System.currentTimeMillis()
            val chooser=test.addMonitor(IntentFilter(Intent.ACTION_CHOOSER),Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true)
            try{onUi{LogBuffer.clear();check(LogBuffer.snapshot().isEmpty());check(save.performClick())};check(chooser.hits==1)}finally{test.removeMonitor(chooser)}
            test.waitForIdleSync();onUi{check(dialog.isShowing);anchor()}
            val file=checkNotNull(dir.listFiles()?.filter{it.lastModified()>=start-2000 && previous[it.name]!=(it.lastModified() to it.length())}?.maxByOrNull{it.lastModified()})
            val exported=file.readText()
            check(exported.contains("TLS_RECORD_SNI:confirmed: BODY/VERIFIED_TWICE, HTTP=200, 98304 B") && exported.contains("↑$up B ↓$down B"))
            check(exported.contains("site-") && !exported.contains("diagnostic-fixture-") && !exported.contains("COPY_EXPORT_FULL_LOG_MARKER"))
            check(exported.contains("--- LOG ---"))
            report.append("PASS actual Copy/Save retain sheet/scroll; full log copy and empty-log export redact snapshot hosts\n")
            onUi{check(close.performClick())};waitUi("Close did not dismiss"){!dialog.isShowing}
            onUi{check(fragment.requireView().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_clear_logs,0))}
            check(diag.presentation(activity,true).rows.isEmpty())
            report.append("PASS actual Close and toolbar Clear\n");code=Activity.RESULT_OK
        }catch(t:Throwable){
            runCatching {
                val b=checkNotNull(test.uiAutomation.takeScreenshot())
                try { File(test.targetContext.getExternalFilesDir(null),"route-diagnostics-$language-failure.png").outputStream().use {
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                } } finally { b.recycle() }
            }
            report.append("FAIL: ${t.stackTraceToString()}\n")
        }
        finally {
            runCatching{onUi{main?.finish()}};diag.clear()
            saved?.let{snapshot->val edit=app.getPreferences().edit().clear();snapshot.forEach{(k,v)->when(v){
                is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v)
                is Set<*>->{@Suppress("UNCHECKED_CAST") edit.putStringSet(k,v as Set<String>)}
            }};if(!edit.commit())code=Activity.RESULT_CANCELED}
            onUi{if(manager!=null)manager.applicationLocales=oldLocale!! else androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(oldCompat)}
        }
        test.finish(code,Bundle().apply{putString("stream",report.toString())})
    }
}
