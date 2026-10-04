package com.smarttools.netguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Keep the visible app task independent of the launcher alias being recolored. */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
