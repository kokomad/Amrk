package com.khader.autorun

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/** شاشة شفافة توقظ الجهاز وتزيل القفل البسيط ثم تفتح الوجهة المطلوبة. */
class WakeActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 45000)

        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { go() }
                override fun onDismissCancelled() { go() }
                override fun onDismissError() { go() }
            })
        } else {
            go()
        }
    }

    @Suppress("DEPRECATION")
    private fun target(): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("target", Intent::class.java)
        else intent.getParcelableExtra("target") as? Intent

    private fun go() {
        if (done) return
        done = true
        try {
            target()?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } catch (e: Exception) {
            // يسجّل الفشل عبر مهلة الخدمة
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
