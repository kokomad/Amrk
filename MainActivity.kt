package com.khader.autorun

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowContentAccess = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = true
        }
        web.addJavascriptInterface(Bridge(this), "Android")
        web.loadUrl("file:///android_asset/index.html")
        Scheduler.rescheduleAll(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        js("window.onNativeResume&&window.onNativeResume()")
    }

    fun js(code: String) {
        runOnUiThread { try { web.evaluateJavascript(code, null) } catch (e: Exception) { } }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.handleBack&&window.handleBack()") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val o = JSONObject().put("number", c.getString(0) ?: "").put("name", c.getString(1) ?: "")
                    js("window.onContact&&window.onContact($o)")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        js("window.onNativeResume&&window.onNativeResume()")
    }
}
