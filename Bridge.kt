package com.khader.autorun

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Bridge(private val a: MainActivity) {
    private val ctx: Context get() = a.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // ───────── المهام ─────────

    @JavascriptInterface
    fun getTasks(): String {
        val arr = JSONArray()
        TaskStore.all(ctx).forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    @JavascriptInterface
    fun saveTask(json: String): String {
        try {
            val o = JSONObject(json)
            val t = Task.fromJson(o)
            val err = validate(t)
            if (err != null) return "ERR:$err"
            val old = if (t.id.isNotEmpty()) TaskStore.get(ctx, t.id) else null
            if (old != null) {
                t.runCount = if (o.optBoolean("resetCount", false)) 0 else old.runCount
                t.lastRun = old.lastRun
                t.lastStatus = old.lastStatus
            }
            if (t.id.isEmpty()) t.id = UUID.randomUUID().toString()
            t.finished = false
            if (t.enabled) {
                Scheduler.schedule(ctx, t)
                if (t.nextRun == 0L) return "ERR:لا توجد مواعيد قادمة. تحقق من تاريخ البدء وعدد الأيام ووقت الإيقاف وأيام الأسبوع"
            } else {
                Scheduler.cancel(ctx, t.id)
                t.nextRun = 0
            }
            TaskStore.upsert(ctx, t)
            return "OK"
        } catch (e: Exception) {
            return "ERR:" + (e.message ?: "خطأ غير معروف")
        }
    }

    private fun validate(t: Task): String? {
        if (!Regex("^\\d{1,2}:\\d{2}$").matches(t.firstTime)) return "وقت التنفيذ غير صالح"
        if (t.startDate.isBlank()) return "اختر تاريخ البدء"
        if (t.timesPerDay !in 1..200) return "عدد المرات في اليوم يجب أن يكون بين 1 و200"
        if (t.timesPerDay > 1 && t.intervalMin < 1) return "الفاصل بين المرات غير صالح"
        val p = t.params
        when (t.type) {
            "whatsapp", "sms" -> {
                if (p.optString("phone").filter { it.isDigit() }.length < 6) return "أدخل رقم هاتف صحيح"
                if (p.optString("message").isBlank()) return "اكتب نص الرسالة"
            }
            "call" -> if (p.optString("phone").filter { it.isDigit() }.length < 3) return "أدخل رقم هاتف صحيح"
            "openapp" -> if (p.optString("pkg").isBlank()) return "اختر تطبيقًا"
            "openurl" -> if (p.optString("url").isBlank()) return "أدخل الرابط"
            "notify" -> if (p.optString("message").isBlank() && p.optString("title").isBlank()) return "اكتب نص التذكير"
        }
        return null
    }

    @JavascriptInterface
    fun deleteTask(id: String) {
        Scheduler.cancel(ctx, id)
        TaskStore.delete(ctx, id)
    }

    @JavascriptInterface
    fun setEnabled(id: String, on: Boolean): String {
        val t = TaskStore.get(ctx, id) ?: return "ERR:المهمة غير موجودة"
        if (on) {
            if (t.finished) t.runCount = 0
            t.finished = false
            t.enabled = true
            Scheduler.schedule(ctx, t)
            if (t.nextRun == 0L) {
                t.enabled = false
                TaskStore.upsert(ctx, t)
                return "ERR:لا توجد مواعيد قادمة لهذه المهمة. عدّل تاريخ البدء أو عدد الأيام"
            }
        } else {
            t.enabled = false
            t.nextRun = 0
            Scheduler.cancel(ctx, id)
        }
        TaskStore.upsert(ctx, t)
        return "OK"
    }

    @JavascriptInterface
    fun runNow(id: String) {
        main.post { Runner.fire(ctx, id, true) }
    }

    // ───────── السجل والإعدادات ─────────

    @JavascriptInterface fun getLogs(): String = LogStore.json(ctx)
    @JavascriptInterface fun clearLogs() { LogStore.clear(ctx) }
    @JavascriptInterface fun getSettings(): String = AppSettings.toJson(ctx).toString()
    @JavascriptInterface fun saveSettings(json: String) { AppSettings.save(ctx, JSONObject(json)) }

    // ───────── النسخ الاحتياطي ─────────

    @JavascriptInterface
    fun exportTasks(): String {
        val arr = JSONArray()
        TaskStore.all(ctx).forEach {
            val o = it.toJson()
            o.remove("id"); o.remove("runCount"); o.remove("lastRun"); o.remove("lastStatus"); o.remove("nextRun"); o.remove("finished")
            arr.put(o)
        }
        return arr.toString(2)
    }

    @JavascriptInterface
    fun importTasks(json: String): String {
        return try {
            val arr = JSONArray(json.trim())
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                o.put("id", "")
                val r = saveTask(o.toString())
                if (r == "OK") n++
            }
            "OK:$n"
        } catch (e: Exception) {
            "ERR:نص النسخة الاحتياطية غير صالح"
        }
    }

    @JavascriptInterface
    fun shareText(text: String) {
        a.runOnUiThread {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            a.startActivity(Intent.createChooser(i, "مشاركة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ───────── الجهاز ─────────

    @JavascriptInterface
    fun getApps(): String {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(i, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.first).put("pkg", it.second)) }
        return arr.toString()
    }

    @JavascriptInterface
    fun getStatus(): String {
        val o = JSONObject()
        val pm = ctx.getSystemService(PowerManager::class.java)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        o.put("accessibility", Util.accEnabled(ctx))
        o.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx))
        o.put("exact", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
        o.put("battery", pm.isIgnoringBatteryOptimizations(ctx.packageName))
        o.put("notif", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        o.put("sms", granted(Manifest.permission.SEND_SMS))
        o.put("call", granted(Manifest.permission.CALL_PHONE))
        o.put("dnd", nm.isNotificationPolicyAccessGranted)
        o.put("sdk", Build.VERSION.SDK_INT)
        return o.toString()
    }

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface
    fun openSetting(name: String) {
        a.runOnUiThread {
            try { doOpen(name) } catch (e: Exception) { toast("تعذّر فتح الإعداد على هذا الجهاز") }
        }
    }

    private fun doOpen(name: String) {
        val pkgUri = Uri.parse("package:" + a.packageName)
        when (name) {
            "accessibility" -> a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "appinfo" -> a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
            "overlay" -> a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri))
            "battery" -> a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
            "dnd" -> a.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            "exact" -> {
                if (Build.VERSION.SDK_INT >= 31) a.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
            }
            "notif" -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                } else {
                    a.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName))
                }
            }
            "sms" -> a.requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 2)
            "call" -> a.requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 3)
        }
    }

    @JavascriptInterface
    fun pickContact() {
        a.runOnUiThread {
            @Suppress("DEPRECATION")
            a.startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), 77)
        }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        a.runOnUiThread {
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        a.runOnUiThread { Toast.makeText(a, msg, Toast.LENGTH_SHORT).show() }
    }
}
