package com.smarttools.netguard

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.smarttools.netguard.model.ConnectionState
import com.smarttools.netguard.service.NotificationHelper
import com.smarttools.netguard.service.TunnelVpnService
import java.io.File

/** Owned API33+ emulator only. Runner must revoke/reset permission flags before invocation.
 * Runtime grant is intentionally left to the outer runner to restore after instrumentation exits:
 * revoking POST_NOTIFICATIONS from inside its own instrumentation can terminate the target process.
 */
internal class NotificationPermissionAndroidProbe(private val test: Instrumentation) {
    private fun ui(action:()->Unit) {
        var failure:Throwable?=null
        test.runOnMainSync { try { action() } catch(t:Throwable) { failure=t } }
        failure?.let { throw it }
    }
    private fun waitFor(message:String, condition:()->Boolean) {
        val end=System.nanoTime()+8_000_000_000L
        while(!condition()) { check(System.nanoTime()<end){message};Thread.sleep(50) }
    }
    private fun onMainCondition(condition:()->Boolean):Boolean {var answer=false;ui{answer=condition()};return answer}
    private fun permissionButton(id:String):AccessibilityNodeInfo? = test.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/$id")?.firstOrNull()
    private fun tapPermission(id:String) {
        waitFor("Runtime permission dialog absent: $id"){permissionButton(id)!=null}
        check(permissionButton(id)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun screenshot(name:String) {
        val bitmap=checkNotNull(test.uiAutomation.takeScreenshot())
        try { File(test.targetContext.getExternalFilesDir(null),name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        } } finally {bitmap.recycle()}
    }
    fun run(blocked:Boolean=false) {
        var main:MainActivity?=null;var saved:Map<String,*>?=null
        var postedFixtureNotification=false
        var code=Activity.RESULT_CANCELED;val report=StringBuilder()
        val app=test.targetContext.applicationContext as App
        val permission=android.Manifest.permission.POST_NOTIFICATIONS
        val manager=test.targetContext.getSystemService(NotificationManager::class.java)
        try {
            require(android.os.Build.VERSION.SDK_INT>=33)
            check(test.targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0){"Debug fixture only"}
            test.waitForIdleSync()
            waitFor("Application not initialized"){runCatching{app.database;true}.getOrDefault(false)}
            check(TunnelVpnService.connectionState.value is ConnectionState.Disconnected){"Owned idle app required"}
            check(test.targetContext.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){"Outer runner must revoke/reset permission before this probe"}
            @Suppress("DEPRECATION")
            val declared=test.targetContext.packageManager.getPackageInfo(test.targetContext.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            check(permission in declared){"POST_NOTIFICATIONS not declared in actual installed manifest"}
            check(manager.activeNotifications.none{it.id==NotificationHelper.NOTIFICATION_ID})
            saved=app.getPreferences().all.toMap()
            app.getPreferences().edit().putBoolean("onboarding_done",true).putBoolean("show_speed_notification",false)
                .putBoolean("notification_permission_requested",blocked).commit()
            main=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            test.waitForIdleSync()
            val activity=main!!
            ui{(activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController.navigate(R.id.nav_settings)}
            test.waitForIdleSync()
            val toggle=activity.findViewById<MaterialSwitch>(R.id.cb_speed_notification)
            ui{toggle.requestRectangleOnScreen(android.graphics.Rect(0,0,toggle.width,toggle.height),true)}
            test.waitForIdleSync()
            ui{check(!toggle.isChecked);toggle.performClick()}
            if(blocked) {
                waitFor("Blocked permission must explain system settings"){test.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText(activity.getString(R.string.notifications_open_settings))?.isNotEmpty()==true}
                check(permissionButton("permission_allow_button")==null){"Permanent denial unexpectedly reopened runtime permission"}
                val monitor=test.addMonitor(IntentFilter(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS),
                    Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true)
                try {
                    val button=test.uiAutomation.rootInActiveWindow!!.findAccessibilityNodeInfosByText(activity.getString(R.string.notifications_open_settings)).first()
                    screenshot("notifications-blocked-settings.png")
                    check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    waitFor("Explicit system settings action was not launched"){monitor.hits==1}
                } finally {test.removeMonitor(monitor)}
                test.waitForIdleSync()
                check(onMainCondition{!toggle.isChecked});check(!app.loadSettings().showSpeedInNotification)
                report.append("PASS actual installed permission; permanent denial uses explicit app-settings action, cancellation leaves toggle off; no runtime loop\n")
            } else {
                screenshot("notifications-runtime-request.png")
                tapPermission("permission_deny_button")
                waitFor("Denied notification did not return to an unchecked app control"){
                    onMainCondition{activity.window.decorView.hasWindowFocus() && !toggle.isChecked} && !app.loadSettings().showSpeedInNotification
                }
                Thread.sleep(400)
                check(permissionButton("permission_allow_button")==null){"Permission denial reopened a request automatically"}
                check(!NotificationHelper.canPost(activity))
                ui{toggle.performClick()}
                tapPermission("permission_allow_button")
                waitFor("Grant did not enable actual notification setting"){onMainCondition{toggle.isChecked} && app.loadSettings().showSpeedInNotification && NotificationHelper.canPost(activity)}
                postedFixtureNotification=true
                NotificationHelper.showConnectedNotification(activity)
                waitFor("Actual service notification not posted after grant"){manager.activeNotifications.any { it.id==NotificationHelper.NOTIFICATION_ID }}
                val notification=manager.activeNotifications.single { it.id==NotificationHelper.NOTIFICATION_ID }
                check(notification.notification.channelId=="net_service")
                fun speedTextVisible()=manager.activeNotifications.firstOrNull { it.id==NotificationHelper.NOTIFICATION_ID }
                    ?.notification?.extras?.getString(android.app.Notification.EXTRA_TEXT)?.contains("\u2193")==true
                NotificationHelper.updateSpeedNotification(activity,0,0)
                waitFor("Speed text was not posted"){speedTextVisible()}
                NotificationHelper.showConnectedNotification(activity)
                waitFor("Plain connected text did not replace speed"){!speedTextVisible()}
                NotificationHelper.updateSpeedNotification(activity,0,0)
                waitFor("Cached idle rates prevented re-enabling speed text"){speedTextVisible()}
                screenshot("notifications-enabled.png")
                ui{toggle.performClick()}
                check(!app.loadSettings().showSpeedInNotification)
                report.append("PASS installed permission; actual first deny keeps off/no repeat; explicit retry grant checks on; actual net_service notification posted and unchanged idle speed restores after plain text; explicit disable persists\n")
                report.append("NOTE outer runner must restore runtime permission after instrumentation exits\n")
            }
            code=Activity.RESULT_OK
        } catch(t:Throwable) {
            runCatching{screenshot("notifications-failure.png")}
            report.append("FAIL ${t.stackTraceToString()}\n")
        } finally {
            if(postedFixtureNotification)manager.cancel(NotificationHelper.NOTIFICATION_ID)
            runCatching {ui{main?.finish()}}
            saved?.let { snapshot->val edit=app.getPreferences().edit().clear();snapshot.forEach{(k,v)->when(v){
                is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v)
                is Set<*>->{@Suppress("UNCHECKED_CAST") edit.putStringSet(k,v as Set<String>)}
            }};if(!edit.commit())code=Activity.RESULT_CANCELED }
        }
        test.finish(code,Bundle().apply{putString("stream",report.toString())})
    }
}
