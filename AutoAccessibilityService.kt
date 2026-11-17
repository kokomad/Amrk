package com.khader.autorun

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * يضغط على الأزرار نيابةً عن المستخدم:
 *  - زر الإرسال في واتساب
 *  - مفاتيح الواي فاي / بيانات الجوال / البلوتوث في لوحات الإعدادات
 */
class AutoAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoAccessibilityService? = null
        private val WA = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val RX_WIFI = Regex("(?i)wi.?fi|wlan|واي|وايفاي|لاسلكي")
        private val RX_DATA = Regex("(?i)mobile data|cellular|mobile network|\\bdata\\b|بيانات|\\bsim\\b|الجوال|المحمول")
        private val RX_AIR = Regex("(?i)airplane|flight|طيران")
        private val RX_BT = Regex("(?i)bluetooth|بلوتوث")
        private val SEND_LABELS = setOf("send", "إرسال", "ارسال")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Pending>()
    private var active: Pending? = null

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun enqueue(p: Pending) {
        queue.addLast(p)
        if (active == null) startNext()
    }

    private fun startNext() {
        val p = queue.removeFirstOrNull() ?: return
        active = p
        p.busy = false
        p.deadline = System.currentTimeMillis() + 40_000
        Runner.wake(this)
        try {
            Runner.startWake(this, p.steps.first().intent)
        } catch (e: Exception) {
            finish(p, false, "تعذّر فتح الشاشة: " + (e.message ?: ""))
            return
        }
        handler.postDelayed(tick, 2500)
    }
    private val tick: Runnable = object : Runnable { : Runnable {
        override fun run() {
            val p = active ?: return
            if (p.busy) return
            if (System.currentTimeMillis() > p.deadline) {
                finish(p, false, "انتهت المهلة: لم تظهر الشاشة المطلوبة (تأكد من أن الجهاز غير مقفل بقفل آمن ومن الصلاحيات)")
                return
            }
            val step = p.steps.first()
            val ok = try { doStep(step) } catch (e: Exception) { false }
            if (!ok) {
             handler.postDelayed(this, 700)
                return
            }
            p.steps.removeFirst()
            p.busy = true
            if (p.steps.isEmpty()) {
                handler.postDelayed({ finish(p, true, "تم التنفيذ") }, 1300)
            } else {
                handler.postDelayed({ launchNext(p) }, 1000)
            }
        }
    }

    private fun launchNext(p: Pending) {
        p.busy = false
        p.deadline = System.currentTimeMillis() + 30_000
        try {
            startActivity(p.steps.first().intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            finish(p, false, "تعذّر فتح الشاشة التالية")
            return
        }
        handler.postDelayed(tick, 2000)
    }

    private fun finish(p: Pending, ok: Boolean, msg: String) {
        handler.removeCallbacks(tick)
        Runner.done(applicationContext, p.taskId, p.taskName, ok, msg)
        if (AppSettings.goHome(this)) performGlobalAction(GLOBAL_ACTION_HOME)
        if (p.wasScreenOff && AppSettings.lockAfter(this) && Build.VERSION.SDK_INT >= 28) {
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }, 900)
        }
        active = null
        handler.postDelayed({ startNext() }, 1500)
    }

    // ───────── تنفيذ الخطوات ─────────

    private fun doStep(step: Step): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        return when (step.kind) {
            "whatsapp" -> {
                if (pkg !in WA) return false
                val n = findSend(root) ?: return false
                clickNode(n)
            }
            else -> {
                if (pkg == packageName) return false
                val list = ArrayList<AccessibilityNodeInfo>()
                collectCheckable(root, list, 0)
                if (list.isEmpty()) return false
                val target = pick(list, step.kind) ?: return false
                val want = step.state == "on"
                if (step.state == "toggle" || target.isChecked != want) clickNode(target) else true
            }
        }
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
            val l = root.findAccessibilityNodeInfosByViewId(id)
            if (!l.isNullOrEmpty()) return l[0]
        }
        for (txt in listOf("إرسال", "Send")) {
            val l = root.findAccessibilityNodeInfosByText(txt) ?: continue
            for (n in l) {
                val d = n.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                if (d in SEND_LABELS) return n
            }
        }
        return null
    }

    private fun collectCheckable(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (n == null || depth > 25) return
        if (n.isCheckable) out.add(n)
        for (i in 0 until n.childCount) collectCheckable(n.getChild(i), out, depth + 1)
    }

    private fun gather(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 6) return
        n.text?.let { sb.append(it).append(' ') }
        n.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until n.childCount) gather(n.getChild(i), sb, depth + 1)
    }

    /** نص الصف الذي ينتمي إليه المفتاح (أول أب يحتوي نصًا). */
    private fun label(n: AccessibilityNodeInfo): String {
        var cur: AccessibilityNodeInfo? = n
        for (i in 0 until 3) {
            cur = cur?.parent ?: break
            val sb = StringBuilder()
            gather(cur, sb, 0)
            if (sb.isNotBlank()) return sb.toString()
        }
        return (n.text ?: n.contentDescription ?: "").toString()
    }

    private fun pick(list: List<AccessibilityNodeInfo>, kind: String): AccessibilityNodeInfo? {
        val labeled = list.map { it to label(it) }.filter { !RX_AIR.containsMatchIn(it.second) }
        return when (kind) {
            "wifi" -> labeled.firstOrNull { RX_WIFI.containsMatchIn(it.second) }?.first
                ?: if (labeled.size == 1) labeled[0].first else null
            "data" -> labeled.firstOrNull { RX_DATA.containsMatchIn(it.second) && !RX_WIFI.containsMatchIn(it.second) }?.first
                ?: labeled.firstOrNull { !RX_WIFI.containsMatchIn(it.second) }?.first
            else -> labeled.firstOrNull { RX_BT.containsMatchIn(it.second) }?.first
                ?: labeled.firstOrNull()?.first
        }
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        for (i in 0..5) {
            val c = cur ?: break
            if (c.isClickable && c.isEnabled && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = c.parent
        }
        return false
    }
}
