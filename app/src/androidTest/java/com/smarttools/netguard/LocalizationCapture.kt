package com.smarttools.netguard

import android.app.Activity
import android.app.Instrumentation
import android.app.LocaleManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.View
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.agent.WbSetupWorker
import com.smarttools.netguard.model.ConnectionState
import com.smarttools.netguard.model.ThemeMode
import com.smarttools.netguard.service.NotificationHelper
import com.smarttools.netguard.service.RouteDiagnostics
import com.smarttools.netguard.service.SiteOrigin
import com.smarttools.netguard.service.TunnelVpnService
import com.smarttools.netguard.ui.logs.DiagnosticsSheet
import com.smarttools.netguard.ui.managed.CreateWbStreamActivity
import com.smarttools.netguard.ui.managed.CreateWbStreamViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Owned idle emulator: resolves/formats real packaged resources and captures localized UI; no probe/VPN. */
internal class LocalizationCapture(private val test: Instrumentation) {
    private fun ui(block:()->Unit) {var failure:Throwable?=null;test.runOnMainSync{try{block()}catch(t:Throwable){failure=t}};failure?.let{throw it}}
    private fun await(label:String,condition:()->Boolean) {
        val end=System.nanoTime()+10_000_000_000L
        while(!condition()){check(System.nanoTime()<end){label};Thread.sleep(30)}
    }
    private fun waitUi(label:String,condition:()->Boolean)=await(label){var value=false;ui{value=condition()};value}
    private val locales=listOf("en","ru","de","zh-CN","ja","hi","tr","ar","es","fr","pt","ko","id","vi","th","it")
    private val spec=Regex("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z])")
    private fun format(raw:String,locale:Locale):String {
        var auto=0
        val args=mutableMapOf<Int,Any>()
        spec.findAll(raw).forEach { match ->
            val kind=match.groupValues[2].lowercase()
            if(kind=="n")return@forEach
            val index=match.groupValues[1].toIntOrNull() ?: ++auto
            args[index]=when(kind){"d","o","x","t"->42L;"e","f","g","a"->1.25;"c"->65;"b"->true;else->"example"}
        }
        return if(args.isEmpty())raw else String.format(locale,raw,*Array(args.keys.max()){args[it+1]?:"example"})
    }
    private fun screenshot(name:String) {
        await("Own app must be foreground before $name"){test.uiAutomation.rootInActiveWindow?.packageName?.toString()==test.targetContext.packageName}
        test.waitForIdleSync()
        Thread.sleep(500) // Complete standard Activity/BottomSheet enter transitions before the visual capture.
        val bitmap=checkNotNull(test.uiAutomation.takeScreenshot())
        try{File(test.targetContext.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    fun run() {
        val app=test.targetContext.applicationContext as App
        var saved:Map<String,*>?=null
        var main:MainActivity?=null
        var wb:CreateWbStreamActivity?=null
        var dialog:DiagnosticsSheet?=null
        var code=Activity.RESULT_CANCELED
        val bundle=Bundle();val report=JSONObject();val matrix=JSONArray()
        val manager=if(Build.VERSION.SDK_INT>=33)test.targetContext.getSystemService(LocaleManager::class.java) else null
        val oldLocales=manager?.applicationLocales
        val oldCompat=androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        try {
            test.waitForIdleSync();await("App initialization"){runCatching{app.database;true}.getOrDefault(false)}
            check(TunnelVpnService.connectionState.value is ConnectionState.Disconnected && CredentialManager.getPort()==null)
            check(runBlocking{app.database.profileDao().getAll()}.isEmpty()){ "Only empty owned-emulator profiles permitted" }
            check(runBlocking{com.smarttools.netguard.agent.ManagedServerRepository.get(app).getById(0)}==null)
            saved=app.getPreferences().all.toMap()
            val nm=app.getSystemService(NotificationManager::class.java)
            val originalChannel=nm.getNotificationChannel("net_service")
            val oldImportance=originalChannel?.importance
            val oldSound=originalChannel?.sound
            val oldBadge=originalChannel?.canShowBadge()
            // WorkManager persists Data while the user can change language or upgrade the APK.
            // Reuse the identical serialized record across locales, preserving opaque diagnostic data.
            val workerArgument="E_QA_42 · sample:443 / 100%"
            val workerData=androidx.work.Data.fromByteArray(
                WbSetupWorker.messageData(app,"error",R.string.wb_setup_failed_details,workerArgument).toByteArray())
            check(workerData.getString("error_key")=="wb_setup_failed_details")
            val workerChecks=JSONArray()
            for(tag in locales) {
                val locale=Locale.forLanguageTag(tag)
                val config=Configuration(app.resources.configuration).apply{setLocale(locale)}
                val context=app.createConfigurationContext(config)
                var stringCount=0;var formatted=0;var pluralCases=0
                R.string::class.java.fields.forEach { field ->
                    val raw=context.getString(field.getInt(null))
                    try {format(raw,locale)}catch(t:Throwable){throw IllegalStateException("$tag string ${field.name}",t)}
                    stringCount++;if(spec.containsMatchIn(raw))formatted++
                }
                R.plurals::class.java.fields.forEach { field ->
                    for(count in listOf(0,1,2,5,21)) {
                        try {format(context.resources.getQuantityText(field.getInt(null),count).toString(),locale)}
                        catch(t:Throwable){throw IllegalStateException("$tag plural ${field.name}/$count",t)}
                        pluralCases++
                    }
                }
                check(app.getPreferences().edit().putString("language",tag).commit())
                if(tag in listOf("en","ru","ar")) {
                    val actual=WbSetupWorker.message(app,workerData,"error",R.string.wb_check_server_state)
                    check(actual==context.getString(R.string.wb_setup_failed_details,workerArgument)) { "$tag persisted WB progress uses stale language" }
                    check(actual.contains(workerArgument)) { "$tag WB raw argument changed" }
                    val legacy=androidx.work.workDataOf("error" to "legacy untranslated prose")
                    check(WbSetupWorker.message(app,legacy,"error",R.string.wb_check_server_state)==context.getString(R.string.wb_check_server_state))
                    val missing=androidx.work.workDataOf("error_key" to "removed_resource_name")
                    check(WbSetupWorker.message(app,missing,"error",R.string.wb_check_server_state)==context.getString(R.string.wb_check_server_state))
                    workerChecks.put(tag)
                }
                NotificationHelper.createChannel(app)
                val channel=checkNotNull(nm.getNotificationChannel("net_service"))
                check(channel.name.toString()==context.getString(R.string.notification_channel_name))
                check(channel.description==context.getString(R.string.notification_channel_description))
                if(originalChannel!=null)check(channel.importance==oldImportance && channel.sound==oldSound && channel.canShowBadge()==oldBadge)
                val notification=NotificationHelper.createConnectedNotification(app)
                check(notification.extras.getString(android.app.Notification.EXTRA_TITLE)==context.getString(R.string.notif_title))
                check(notification.actions.single().title.toString()==context.getString(R.string.notif_stop))
                matrix.put(JSONObject().put("locale",tag).put("strings",stringCount).put("formatted",formatted).put("pluralCases",pluralCases).put("channelUpdatedPreservingSettings",true))
            }
            report.put("resolvedResourceMatrix",matrix)
            report.put("sameSerializedWorkerDataCurrentLocaleAndRawArgument",workerChecks)
            val captures=JSONArray()
            for(tag in listOf("ar","de","ja")) {
                app.saveSettings(app.loadSettings().copy(language=tag,themeMode=ThemeMode.DARK,showConnectionMap=false))
                app.getPreferences().edit().putBoolean("onboarding_done",true).commit()
                ui{if(manager!=null)manager.applicationLocales=LocaleList.forLanguageTags(tag)
                    else androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(tag))}
                main=test.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val activity=main!!
                waitUi("$tag Activity locale/focus"){activity.resources.configuration.locales[0].language==tag && activity.window.decorView.hasWindowFocus()}
                check(activity.resources.configuration.layoutDirection==if(tag=="ar")View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
                for((id,name) in listOf(R.id.nav_home to "home",R.id.nav_settings to "settings",R.id.nav_subscriptions to "subscriptions",R.id.nav_logs to "logs")) {
                    ui{activity.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId=id}
                    test.waitForIdleSync();Thread.sleep(200)
                    val file="localization-$tag-$name.png";screenshot(file);captures.put(file)
                }
                ui{
                    dialog=DiagnosticsSheet(activity,copy={},save={}).also{
                        it.update(RouteDiagnostics().presentation(activity,true),false);it.show()
                    }
                }
                waitUi("$tag diagnostic actions laid out") {dialog?.isShowing==true && dialog?.window?.decorView?.hasWindowFocus()==true && dialog?.window?.decorView?.isLayoutRequested==false}
                val file="localization-$tag-diagnostics.png";screenshot(file);captures.put(file)
                val diagnostic=RouteDiagnostics()
                val token=diagnostic.begin(true)
                val origin=SiteOrigin("localization-fixture.invalid",443,true)
                diagnostic.phase(token,origin,"CHECKED")
                diagnostic.catalog(token,origin,"VERIFIED_TWICE")
                diagnostic.flow(token,origin,"TLS_RECORD_SNI","FIRST_OR_CACHE").apply{progress(4096,65536);close(false)}
                ui{dialog!!.update(diagnostic.presentation(activity,true),false)}
                fun findList(view:View):androidx.recyclerview.widget.RecyclerView? {
                    if(view is androidx.recyclerview.widget.RecyclerView)return view
                    if(view is android.view.ViewGroup)for(i in 0 until view.childCount)findList(view.getChildAt(i))?.let{return it}
                    return null
                }
                waitUi("$tag populated diagnostic row committed") {
                    val list=findList(dialog!!.window!!.decorView)
                    list?.findViewHolderForAdapterPosition(0)?.itemView?.contentDescription?.contains(origin.host)==true
                }
                ui{findList(dialog!!.window!!.decorView)!!.findViewHolderForAdapterPosition(0)!!.itemView.performClick()}
                test.waitForIdleSync();Thread.sleep(200)
                val populated="localization-$tag-diagnostics-populated.png";screenshot(populated);captures.put(populated)
                ui{dialog?.dismiss();dialog=null;activity.finish()};test.waitForIdleSync();main=null
                wb=test.startActivitySync(Intent(app,CreateWbStreamActivity::class.java).putExtra(CreateWbStreamActivity.EXTRA_SERVER_ID,0L).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CreateWbStreamActivity
                val wizard=wb!!
                waitUi("$tag WB missing-server boundary") {
                    val state=androidx.lifecycle.ViewModelProvider(wizard)[CreateWbStreamViewModel::class.java].state.value
                    wizard.window.decorView.hasWindowFocus() && !state.busy && !state.prepared && state.error!=null
                }
                ui {
                    val field=CreateWbStreamActivity::class.java.getDeclaredField("web").apply{isAccessible=true}
                    val web=field.get(wizard) as android.webkit.WebView
                    check(web.url.isNullOrEmpty() || web.url=="about:blank") { "WB capture unexpectedly loaded network content" }
                }
                val wbFile="localization-$tag-wb.png";screenshot(wbFile);captures.put(wbFile)
                ui{CreateWbStreamActivity::class.java.getDeclaredMethod("pasteRoom").apply{isAccessible=true}.invoke(wizard)}
                waitUi("$tag WB paste dialog focus") {!wizard.window.decorView.hasWindowFocus()}
                test.waitForIdleSync();Thread.sleep(200)
                val paste="localization-$tag-wb-paste.png";screenshot(paste);captures.put(paste)
                test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                waitUi("$tag WB paste dismissed") {wizard.window.decorView.hasWindowFocus()}
                ui{wizard.finish()};test.waitForIdleSync();wb=null
            }
            report.put("screenshotsForIndependentReview",captures)
            report.put("scope","16 packaged-resource format/plural and notification-language checks; AR/DE/JA main tabs, empty/populated diagnostics, WB missing-server and paste-dialog captures. No network/VPN probe; screenshots require manual review.")
            code=Activity.RESULT_OK
        }catch(t:Throwable){report.put("failure",t.stackTraceToString())}
        finally {
            ui{dialog?.dismiss();wb?.finish();main?.finish()}
            saved?.let { values ->
                val editor=app.getPreferences().edit().clear()
                values.forEach{(k,v)->when(v){is String->editor.putString(k,v);is Boolean->editor.putBoolean(k,v);is Int->editor.putInt(k,v);is Long->editor.putLong(k,v);is Float->editor.putFloat(k,v);is Set<*>->@Suppress("UNCHECKED_CAST") editor.putStringSet(k,v as Set<String>)}}
                check(editor.commit())
                ui{if(manager!=null&&oldLocales!=null)manager.applicationLocales=oldLocales else androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(oldCompat)}
                NotificationHelper.invalidateCache();NotificationHelper.createChannel(app)
                val restored=app.loadSettings()
                com.smarttools.netguard.util.LauncherIcons.sync(app,restored.launcherIconTheme?:restored.themeMode)
            }
        }
        File(app.getExternalFilesDir(null),"localization-capture.json").writeText(report.toString(2))
        bundle.putString("stream",if(code==Activity.RESULT_OK)"LOCALIZATION CAPTURE COMPLETE:16 locale format/channel matrix;24 UI screenshots require review\n" else report.toString(2))
        test.finish(code,bundle)
    }
}
