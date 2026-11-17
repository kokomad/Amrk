package com.khader.autorun

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ───────────────────────── النموذج ─────────────────────────
data class Task(
    var id: String = "",
    var name: String = "",
    var type: String = "whatsapp",
    var params: JSONObject = JSONObject(),
    var startDate: String = "",
    var firstTime: String = "09:00",
    var timesPerDay: Int = 1,
    var intervalMin: Int = 60,
    var days: Int = 1,
    var weekdays: List<Int> = emptyList(),
    var stopAt: String = "",
    var maxRuns: Int = 0,
    var enabled: Boolean = true,
    var runCount: Int = 0,
    var lastRun: Long = 0,
    var lastStatus: String = "",
    var nextRun: Long = 0,
    var finished: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("type", type); put("params", params)
        put("startDate", startDate); put("firstTime", firstTime)
        put("timesPerDay", timesPerDay); put("intervalMin", intervalMin); put("days", days)
        put("weekdays", JSONArray(weekdays)); put("stopAt", stopAt); put("maxRuns", maxRuns)
        put("enabled", enabled); put("runCount", runCount); put("lastRun", lastRun)
        put("lastStatus", lastStatus); put("nextRun", nextRun); put("finished", finished)
    }

    companion object {
        fun fromJson(o: JSONObject): Task {
            val wd = ArrayList<Int>()
            o.optJSONArray("weekdays")?.let { for (i in 0 until it.length()) wd.add(it.getInt(i)) }
            return Task(
                id = o.optString("id", ""),
                name = o.optString("name", "").trim(),
                type = o.optString("type", "whatsapp"),
                params = o.optJSONObject("params") ?: JSONObject(),
                startDate = o.optString("startDate", ""),
                firstTime = o.optString("firstTime", "09:00"),
                timesPerDay = o.optInt("timesPerDay", 1),
                intervalMin = o.optInt("intervalMin", 60),
                days = o.optInt("days", 1),
                weekdays = wd,
                stopAt = o.optString("stopAt", ""),
                maxRuns = o.optInt("maxRuns", 0),
                enabled = o.optBoolean("enabled", true),
                runCount = o.optInt("runCount", 0),
                lastRun = o.optLong("lastRun", 0),
                lastStatus = o.optString("lastStatus", ""),
                nextRun = o.optLong("nextRun", 0),
                finished = o.optBoolean("finished", false)
            )
        }
    }
}

// ───────────────────────── حساب الموعد القادم ─────────────────────────
object Schedule {
    /** يعيد وقت التنفيذ القادم بعد [after] أو 0 إذا انتهت الجدولة. */
    fun next(t: Task, after: Long): Long {
        if (t.maxRuns > 0 && t.runCount >= t.maxRuns) return 0
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val start = Calendar.getInstance()
        try { start.time = df.parse(t.startDate) ?: Date() } catch (e: Exception) { start.time = Date() }
        val parts = t.firstTime.split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 9
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        val stop = parseStop(t.stopAt)
        val maxDays = if (t.days > 0) t.days else 3650
        val times = if (t.timesPerDay < 1) 1 else t.timesPerDay
        for (d in 0 until maxDays) {
            val day = start.clone() as Calendar
            day.add(Calendar.DAY_OF_YEAR, d)
            if (t.weekdays.isNotEmpty() && !t.weekdays.contains(day.get(Calendar.DAY_OF_WEEK))) continue
            for (k in 0 until times) {
                val c = day.clone() as Calendar
                c.set(Calendar.HOUR_OF_DAY, h)
                c.set(Calendar.MINUTE, m)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                c.add(Calendar.MINUTE, k * t.intervalMin)
                val ms = c.timeInMillis
                if (ms > stop) return 0
                if (ms > after) return ms
            }
        }
        return 0
    }

    private fun parseStop(s: String): Long {
        if (s.isBlank()) return Long.MAX_VALUE
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(s)?.time ?: Long.MAX_VALUE
        } catch (e: Exception) { Long.MAX_VALUE }
    }
}

// ───────────────────────── التخزين ─────────────────────────
object TaskStore {
    private const val P = "autorun"
    private const val K = "tasks"

    @Synchronized
    fun all(c: Context): MutableList<Task> {
        val s = c.getSharedPreferences(P, Context.MODE_PRIVATE).getString(K, "[]") ?: "[]"
        val a = try { JSONArray(s) } catch (e: Exception) { JSONArray() }
        return MutableList(a.length()) { Task.fromJson(a.getJSONObject(it)) }
    }

    @Synchronized
    private fun write(c: Context, l: List<Task>) {
        val a = JSONArray()
        l.forEach { a.put(it.toJson()) }
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString(K, a.toString()).apply()
    }

    @Synchronized fun get(c: Context, id: String): Task? = all(c).firstOrNull { it.id == id }

    @Synchronized
    fun upsert(c: Context, t: Task) {
        val l = all(c)
        val i = l.indexOfFirst { it.id == t.id }
        if (i >= 0) l[i] = t else l.add(t)
        write(c, l)
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        val l = all(c)
        l.removeAll { it.id == id }
        write(c, l)
    }

    @Synchronized
    fun setStatus(c: Context, id: String, s: String) {
        val l = all(c)
        val t = l.firstOrNull { it.id == id } ?: return
        t.lastStatus = s
        write(c, l)
    }
}

object LogStore {
    private const val P = "autorun_log"
    private const val K = "log"

    @Synchronized
    fun add(c: Context, name: String, ok: Boolean, msg: String) {
        val old = try { JSONArray(c.getSharedPreferences(P, Context.MODE_PRIVATE).getString(K, "[]")) } catch (e: Exception) { JSONArray() }
        val n = JSONArray()
        n.put(JSONObject().put("t", System.currentTimeMillis()).put("name", name).put("ok", ok).put("msg", msg))
        for (i in 0 until minOf(old.length(), 299)) n.put(old.get(i))
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString(K, n.toString()).apply()
    }

    @Synchronized
    fun json(c: Context): String = c.getSharedPreferences(P, Context.MODE_PRIVATE).getString(K, "[]") ?: "[]"

    @Synchronized
    fun clear(c: Context) { c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().remove(K).apply() }
}

object AppSettings {
    private fun p(c: Context) = c.getSharedPreferences("autorun_settings", Context.MODE_PRIVATE)
    fun lockAfter(c: Context) = p(c).getBoolean("lockAfter", true)
    fun goHome(c: Context) = p(c).getBoolean("goHome", true)
    fun notifyFail(c: Context) = p(c).getBoolean("notifyFail", true)
    fun countryCode(c: Context) = p(c).getString("countryCode", "") ?: ""

    fun toJson(c: Context): JSONObject = JSONObject()
        .put("lockAfter", lockAfter(c)).put("goHome", goHome(c))
        .put("notifyFail", notifyFail(c)).put("countryCode", countryCode(c))

    fun save(c: Context, o: JSONObject) {
        p(c).edit()
            .putBoolean("lockAfter", o.optBoolean("lockAfter", true))
            .putBoolean("goHome", o.optBoolean("goHome", true))
            .putBoolean("notifyFail", o.optBoolean("notifyFail", true))
            .putString("countryCode", o.optString("countryCode", "").filter { it.isDigit() })
            .apply()
    }
}

// ───────────────────────── الإشعارات ─────────────────────────
object Notifier {
    private const val CH = "autorun_main"

    fun show(ctx: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "منفّذ", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }
}

// ───────────────────────── المنبّهات ─────────────────────────
object Scheduler {
    private fun pi(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction("com.khader.autorun.RUN").putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id))
    }

    /** يحسب الموعد القادم ويضبط المنبّه، ويكتب الموعد في t.nextRun (بدون حفظ). */
    fun schedule(ctx: Context, t: Task) {
        cancel(ctx, t.id)
        t.nextRun = if (t.enabled) Schedule.next(t, System.currentTimeMillis()) else 0
        if (t.nextRun == 0L) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, t.id)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t.nextRun, show), p)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.nextRun, p)
        }
    }

    fun rescheduleAll(ctx: Context) {
        for (t in TaskStore.all(ctx)) {
            if (!t.enabled || t.finished) continue
            schedule(ctx, t)
            if (t.nextRun == 0L) { t.enabled = false; t.finished = true }
            TaskStore.upsert(ctx, t)
        }
    }
}

object Util {
    fun accEnabled(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(c, AutoAccessibilityService::class.java)
        return s.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun normPhone(ctx: Context, raw: String): String {
        val plus = raw.trim().startsWith("+")
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("00")) {
            d = d.substring(2)
        } else if (!plus && d.startsWith("0")) {
            val cc = AppSettings.countryCode(ctx)
            if (cc.isNotEmpty()) d = cc + d.trimStart('0')
        }
        return d
    }
}
