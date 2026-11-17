package com.khader.autorun

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SmsManager

/** خطوة واجهة (تُنفَّذ عبر خدمة الوصول) */
class Step(val kind: String, val state: String, val intent: Intent)

class Pending(
    val taskId: String,
    val taskName: String,
    val steps: ArrayDeque<Step>,
    val wasScreenOff: Boolean
) {
    var deadline = 0L
    var busy = false
}

object Runner {

    fun fire(ctx: Context, id: String, test: Boolean) {
        val t = TaskStore.get(ctx, id) ?: return
        if (!test) {
            if (!t.enabled) return
            t.runCount++
            t.lastRun = System.currentTimeMillis()
            Scheduler.schedule(ctx, t)
            if (t.nextRun == 0L) { t.finished = true; t.enabled = false }
            TaskStore.upsert(ctx, t)
        }
        execute(ctx, t)
    }

    private fun execute(ctx: Context, t: Task) {
        try {
            when (t.type) {
                "sms" -> sms(ctx, t)
                "whatsapp" -> whatsapp(ctx, t)
                "call" -> call(ctx, t)
                "wifi", "data", "bluetooth" ->
                    accSteps(ctx, t, listOfNotNull(makeStep(ctx, t.type, t.params.optString("state", "on"))))
                "netswitch" -> netSwitch(ctx, t)
                "flashlight" -> torch(ctx, t)
                "sound" -> sound(ctx, t)
                "openapp" -> openApp(ctx, t)
                "openurl" -> openUrl(ctx, t)
                "notify" -> {
                    Notifier.show(ctx, t.params.optString("title").ifBlank { t.name }, t.params.optString("message"))
                    done(ctx, t.id, t.name, true, "أُرسل التذكير")
                }
                else -> done(ctx, t.id, t.name, false, "نوع أمر غير معروف")
            }
        } catch (e: Exception) {
            done(ctx, t.id, t.name, false, "خطأ: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    fun done(ctx: Context, id: String, name: String, ok: Boolean, msg: String) {
        LogStore.add(ctx, name, ok, msg)
        TaskStore.setStatus(ctx, id, (if (ok) "✅ " else "❌ ") + msg)
        if (!ok && AppSettings.notifyFail(ctx)) Notifier.show(ctx, "تعذّر تنفيذ: $name", msg)
    }

    // ───────── أوامر مباشرة (بدون واجهة) ─────────

    @Suppress("DEPRECATION")
    private fun sms(ctx: Context, t: Task) {
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return done(ctx, t.id, t.name, false, "صلاحية إرسال الرسائل غير ممنوحة")
        val phone = t.params.optString("phone").filter { it.isDigit() || it == '+' }
        val msg = t.params.optString("message")
        if (phone.length < 5) return done(ctx, t.id, t.name, false, "رقم غير صالح")
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sm.sendMultipartTextMessage(phone, null, sm.divideMessage(msg), null, null)
        done(ctx, t.id, t.name, true, "أُرسلت SMS إلى $phone")
    }

    private fun torch(ctx: Context, t: Task) {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return done(ctx, t.id, t.name, false, "لا يوجد فلاش في الجهاز")
        val on = t.params.optString("state", "on") == "on"
        cm.setTorchMode(id, on)
        done(ctx, t.id, t.name, true, if (on) "تم تشغيل الكشاف" else "تم إطفاء الكشاف")
    }

    private fun sound(ctx: Context, t: Task) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val mode = t.params.optString("mode", "normal")
        var note = ""
        try {
            when (mode) {
                "silent" -> if (nm.isNotificationPolicyAccessGranted) {
                    am.ringerMode = AudioManager.RINGER_MODE_SILENT
                } else {
                    am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    note = " (اهتزاز: وضع الصامت يحتاج صلاحية عدم الإزعاج)"
                }
                "vibrate" -> am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                else -> am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
        } catch (e: SecurityException) {
            return done(ctx, t.id, t.name, false, "النظام رفض تغيير وضع الصوت (فعّل صلاحية عدم الإزعاج)")
        }
        val v = t.params.optInt("volume", -1)
        if (v >= 0) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, 100) * max / 100, 0)
        }
        val label = when (mode) { "silent" -> "صامت"; "vibrate" -> "اهتزاز"; else -> "رنين" }
        done(ctx, t.id, t.name, true, "وضع الصوت: $label$note")
    }

    // ───────── أوامر تفتح شاشة ─────────

    private fun canLaunch(ctx: Context, t: Task): Boolean {
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(ctx)) {
            done(ctx, t.id, t.name, false, "فعّل صلاحية «العرض فوق التطبيقات»")
            return false
        }
        return true
    }

    private fun launchOnly(ctx: Context, t: Task, intent: Intent, okMsg: String) {
        if (!canLaunch(ctx, t)) return
        wake(ctx)
        startWake(ctx, intent)
        done(ctx, t.id, t.name, true, okMsg)
    }

    private fun call(ctx: Context, t: Task) {
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return done(ctx, t.id, t.name, false, "صلاحية إجراء المكالمات غير ممنوحة")
        val num = t.params.optString("phone").filter { it.isDigit() || it == '+' }
        if (num.length < 3) return done(ctx, t.id, t.name, false, "رقم غير صالح")
        launchOnly(ctx, t, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))), "بدأ الاتصال بـ $num")
    }

    private fun openApp(ctx: Context, t: Task) {
        val i = ctx.packageManager.getLaunchIntentForPackage(t.params.optString("pkg"))
            ?: return done(ctx, t.id, t.name, false, "التطبيق غير مثبّت")
        launchOnly(ctx, t, i, "فُتح التطبيق")
    }

    private fun openUrl(ctx: Context, t: Task) {
        var u = t.params.optString("url").trim()
        if (u.isEmpty()) return done(ctx, t.id, t.name, false, "رابط فارغ")
        if (!u.contains("://")) u = "https://$u"
        launchOnly(ctx, t, Intent(Intent.ACTION_VIEW, Uri.parse(u)), "فُتح الرابط")
    }

    // ───────── أوامر تحتاج خدمة الوصول ─────────

    private fun installed(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun whatsapp(ctx: Context, t: Task) {
        val phone = Util.normPhone(ctx, t.params.optString("phone"))
        if (phone.length < 6) return done(ctx, t.id, t.name, false, "رقم غير صالح")
        var pkg = t.params.optString("waPackage", "auto")
        if (pkg == "auto" || !installed(ctx, pkg)) {
            pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(ctx, it) }
                ?: return done(ctx, t.id, t.name, false, "واتساب غير مثبّت")
        }
        val uri = Uri.parse("https://wa.me/$phone?text=" + Uri.encode(t.params.optString("message")))
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
        accSteps(ctx, t, listOf(Step("whatsapp", "", intent)))
    }

    private fun netSwitch(ctx: Context, t: Task) {
        val toData = t.params.optString("mode", "wifi_to_data") == "wifi_to_data"
        val steps = if (toData) listOfNotNull(makeStep(ctx, "wifi", "off"), makeStep(ctx, "data", "on"))
        else listOfNotNull(makeStep(ctx, "data", "off"), makeStep(ctx, "wifi", "on"))
        accSteps(ctx, t, steps)
    }

    private fun panelIntent(kind: String): Intent = when (kind) {
        "wifi" -> Intent(if (Build.VERSION.SDK_INT >= 30) Settings.Panel.ACTION_WIFI else Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        "data" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        else Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
        else -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    }

    /** يعيد null إذا نُفّذ الأمر مباشرة عبر الـ API (أندرويد القديم). */
    @Suppress("DEPRECATION")
    private fun makeStep(ctx: Context, kind: String, state: String): Step? {
        if (kind == "wifi" && Build.VERSION.SDK_INT < 29) {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled = when (state) { "on" -> true; "off" -> false; else -> !wm.isWifiEnabled }
            return null
        }
        if (kind == "bluetooth" && Build.VERSION.SDK_INT < 31) {
            val bt = BluetoothAdapter.getDefaultAdapter() ?: throw IllegalStateException("لا يوجد بلوتوث في الجهاز")
            val want = when (state) { "on" -> true; "off" -> false; else -> !bt.isEnabled }
            if (want) bt.enable() else bt.disable()
            return null
        }
        return Step(kind, state, panelIntent(kind))
    }

    private fun accSteps(ctx: Context, t: Task, steps: List<Step>) {
        if (steps.isEmpty()) return done(ctx, t.id, t.name, true, "تم التنفيذ")
        if (!canLaunch(ctx, t)) return
        val svc = AutoAccessibilityService.instance
            ?: return done(ctx, t.id, t.name, false, "فعّل خدمة الوصول (Accessibility) من تبويب الصلاحيات")
        val pm = ctx.getSystemService(PowerManager::class.java)
        svc.enqueue(Pending(t.id, t.name, ArrayDeque(steps), !pm.isInteractive))
    }

    // ───────── مساعدات ─────────

    @Suppress("DEPRECATION")
    fun wake(ctx: Context) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "autorun:wake"
        )
        wl.acquire(90_000L)
    }

    fun startWake(ctx: Context, target: Intent) {
        val w = Intent(ctx, WakeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra("target", target)
        ctx.startActivity(w)
    }
}
