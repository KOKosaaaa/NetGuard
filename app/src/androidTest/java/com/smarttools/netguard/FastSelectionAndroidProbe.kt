package com.smarttools.netguard

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.model.*
import com.smarttools.netguard.service.*
import com.smarttools.netguard.util.GeoLookup
import com.smarttools.netguard.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.io.File

/** Owned idle emulator only. Holds isolated-selector admission; no native core, DNS or VPN launch. */
internal class FastSelectionAndroidProbe(private val test: Instrumentation) {
    private fun ui(block:()->Unit) {var failure:Throwable?=null;test.runOnMainSync{try{block()}catch(t:Throwable){failure=t}};failure?.let{throw it}}
    private fun await(ms:Long,label:String,condition:()->Boolean) {
        val end=System.nanoTime()+ms*1_000_000
        while(!condition()){check(System.nanoTime()<end){label};Thread.sleep(20)}
    }
    private fun waitUi(ms:Long,label:String,condition:()->Boolean)=await(ms,label){var ok=false;ui{ok=condition()};ok}
    @Suppress("UNCHECKED_CAST") private fun <T> flow(name:String)=TunnelVpnService::class.java.getDeclaredField(name).apply{isAccessible=true}.get(null) as MutableStateFlow<T>
    private fun job(vm:MainViewModel)=MainViewModel::class.java.getDeclaredField("autoSelectJob").apply{isAccessible=true}.get(vm) as? Job
    fun run() {
        val app=test.targetContext.applicationContext as App
        val connection=flow<ConnectionState>("_connectionState");val active=flow<Long>("_activeProfileId")
        val oldConnection=connection.value;val oldActive=active.value
        var saved:Map<String,*>?=null;var a:MainActivity?=null;val inserted=mutableListOf<Long>()
        var admitted=false;var held=false;var ok=false
        val owner=Any();val mutex=ServerQualitySelector::class.java.getDeclaredField("mutex").apply{isAccessible=true}.get(null) as Mutex
        val geo=GeoLookup::class.java.getDeclaredField("cachedUserLocation").apply{isAccessible=true}
        val geoTime=GeoLookup::class.java.getDeclaredField("cachedTimestamp").apply{isAccessible=true}
        val oldGeo=geo.get(null);val oldGeoTime=geoTime.getLong(null)
        val report=StringBuilder("SCOPE Android actual Best Server button, injected session/cache, selector mutex held; no native probe or VPN transport\n")
        try {
            test.waitForIdleSync();await(8000,"App bootstrap"){runCatching{app.database;true}.getOrDefault(false)}
            check(app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
            check(oldConnection is ConnectionState.Disconnected && CredentialManager.getPort()==null)
            check(runBlocking{app.database.profileDao().getAll()}.isEmpty()){ "Requires empty owned-emulator profile fixture; real profiles untouched" }
            admitted=true;saved=app.getPreferences().all.toMap()
            app.getPreferences().edit().putBoolean("onboarding_done",true).commit()
            app.saveSettings(app.loadSettings().copy(showConnectionMap=false,showSpeedTest=false))
            geo.set(null,GeoLookup.LatLon(55.8,37.6));geoTime.setLong(null,System.currentTimeMillis())
            val ids=(1..2).map { n -> runBlocking { app.database.profileDao().insert(ServerProfile(name="QA Helsinki selection $n",protocol=Protocol.VLESS,address="qa-selection.invalid")) }.also{inserted+=it} }
            a=test.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity=a!!;val vm=activity.mainViewModel
            test.waitForIdleSync()
            val button=activity.findViewById<View>(R.id.btn_auto_select)
            val spinner=activity.findViewById<View>(R.id.progress_auto_select)
            fun assertIdle() {check(!vm.autoSelecting.value && spinner.visibility==View.GONE && button.isEnabled && button.alpha==1f)}
            fun clickPending():Job {
                ui {check(button.isEnabled && button.performClick());check(vm.autoSelecting.value)}
                waitUi(700,"Selection never showed busy state before mutex deadline") {vm.autoSelecting.value && spinner.visibility==View.VISIBLE && !button.isEnabled}
                lateinit var pending:Job
                ui {pending=checkNotNull(job(vm));check(pending.isActive);activity.decorateGlass(activity.findViewById(R.id.home_scroll));check(button.alpha==0f && spinner.visibility==View.VISIBLE)}
                return pending
            }
            runBlocking{mutex.lock(owner)};held=true
            val admissionStart=System.nanoTime()
            val admission=clickPending()
            waitUi(1800,"Occupied selector admission left spinner running") {!vm.autoSelecting.value && spinner.visibility==View.GONE}
            runBlocking{withTimeout(500){admission.join()}}
            check((System.nanoTime()-admissionStart)/1_000_000<2400)
            ui {assertIdle();check(connection.value is ConnectionState.Disconnected && CredentialManager.getPort()==null)}
            report.append("PASS occupied native admission expires with spinner/button restored under2400ms; zero native startup\n")
            // Reproduce the reported overlap: a separate connection completes while selection is pending.
            val first=clickPending();val connected=ConnectionState.Connected()
            ui {active.value=ids[1];connection.value=connected}
            waitUi(700,"New connected session did not cancel selection/spinner") {!vm.autoSelecting.value && first.isCancelled && spinner.visibility==View.GONE}
            runBlocking{withTimeout(1500){first.join()}}
            ui {assertIdle();check(connection.value===connected && active.value==ids[1]);check(CredentialManager.getPort()==null)}
            report.append("PASS new Connected cancels real pending choice, spinner gone/button restored; no native startup\n")
            ui {connection.value=ConnectionState.Disconnected;active.value=-1}
            test.waitForIdleSync()
            val old=clickPending()
            // Launch a new choice in the same UI turn as manual selection cancels the previous one.
            ui {vm.selectProfile(ids[1]);vm.autoSelectAndConnect()}
            waitUi(700,"Old cancellation hid newer selection") {old.isCancelled && vm.autoSelecting.value && spinner.visibility==View.VISIBLE}
            lateinit var replacement:Job
            ui {replacement=checkNotNull(job(vm));check(replacement!==old && replacement.isActive)}
            runBlocking{withTimeout(1000){old.join()}}
            ui {check(vm.autoSelecting.value && spinner.visibility==View.VISIBLE)}
            val nextConnected=ConnectionState.Connected()
            ui {active.value=ids[1];connection.value=nextConnected}
            waitUi(700,"Replacement selection not cancelled on connection") {!vm.autoSelecting.value && replacement.isCancelled}
            runBlocking{withTimeout(1000){replacement.join()}}
            ui {assertIdle();check(CredentialManager.getPort()==null)}
            report.append("PASS manual profile action cancels old request; old finally cannot clear newer spinner; later Connected cancels replacement\n")
            // Mutex remains occupied: a complete cached winner must bypass expensive admission.
            val profiles=runBlocking{app.database.profileDao().getAll()}
            val settings=app.loadSettings();val network=checkNotNull(ServerQualitySelector.networkKey(app))
            val targets=ServerQualitySelector.targets(settings)
            check(targets.size>=2)
            profiles.forEach { p -> ServerQualityCache.put(p,network,targets,
                ServerQuality(targets.associateWith{ServiceAnswer(Reachability.AVAILABLE,if(p.id==ids[1])20 else 900)}),settings) }
            val before=System.nanoTime()
            ui {check(button.performClick())}
            waitUi(700,"Cached choice unnecessarily waited for held native-probe mutex") {!vm.autoSelecting.value && spinner.visibility==View.GONE && button.alpha==1f && button.isEnabled}
            check((System.nanoTime()-before)/1_000_000<900)
            check(runBlocking{app.database.profileDao().getAll()}.single{it.isSelected}.id==ids[1])
            ui {assertIdle();check(connection.value===nextConnected && active.value==ids[1]);check(CredentialManager.getPort()==null)}
            report.append("PASS ranked cached current server completes under900ms despite occupied native mutex; connection identity preserved and no speculative startup\n")
            waitUi(3000,"Actual activity did not retain foreground focus"){activity.window.decorView.hasWindowFocus()}
            await(8000,"Own app inaccessible before screenshot"){test.uiAutomation.rootInActiveWindow?.packageName?.toString()==app.packageName}
            test.uiAutomation.takeScreenshot()?.let{b->try{File(app.getExternalFilesDir(null),"fast-selection-connected.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
            ok=true
        }catch(t:Throwable){report.append("FAIL ${t.stackTraceToString()}\n")}
        finally {
            if(admitted) {
                // Cancel before releasing admission even on a failed assertion: never admit a native fixture.
                ui {a?.let{job(it.mainViewModel)?.cancel()};connection.value=ConnectionState.Disconnected;a?.finish()}
                test.waitForIdleSync()
                if(held){mutex.unlock(owner);held=false}
                connection.value=oldConnection;active.value=oldActive
                inserted.forEach{id->runBlocking{app.database.profileDao().deleteById(id)}}
                geo.set(null,oldGeo);geoTime.setLong(null,oldGeoTime)
                saved?.let{values->val e=app.getPreferences().edit().clear();values.forEach{(k,v)->when(v){is String->e.putString(k,v);is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v);is Float->e.putFloat(k,v);is Set<*>->{@Suppress("UNCHECKED_CAST") e.putStringSet(k,v as Set<String>)}}};check(e.commit())}
            }
        }
        test.finish(if(ok)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString("stream",report.toString())})
    }
}
