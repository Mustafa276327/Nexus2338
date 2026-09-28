package com.nexus.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/* =========================================================
   NEXUS BROWSER - Android (native WebView tabanlı)
   Faz 1 + Faz 2 + Faz 3 + Faz 4
   ========================================================= */

private const val APP_VERSION_CODE = 1

private val TRACKER_DOMAINS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
    "googletagmanager.com", "googletagservices.com", "adservice.google.com",
    "connect.facebook.net", "ads.facebook.com", "scorecardresearch.com", "outbrain.com",
    "taboola.com", "criteo.com", "criteo.net", "adnxs.com", "amazon-adsystem.com",
    "moatads.com", "quantserve.com", "hotjar.com", "mixpanel.com", "segment.io",
    "segment.com", "appsflyer.com", "branch.io", "mc.yandex.ru", "adsrvr.org",
    "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com",
    "bidswitch.net", "smartadserver.com", "adform.net"
)

private val IMAGE_EXT_REGEX = Regex("\\.(png|jpe?g|gif|webp|bmp)(\\?|$)", RegexOption.IGNORE_CASE)

private const val FINGERPRINT_JS = """
(function(){
  try {
    if (window.__nexusFpHooked) return; window.__nexusFpHooked = true;
    var notify = function(t){ try { if (window.NexusFp) NexusFp.report(t); } catch(e){} };
    if (window.HTMLCanvasElement) {
      var o1 = HTMLCanvasElement.prototype.toDataURL;
      HTMLCanvasElement.prototype.toDataURL = function(){ notify('canvas'); return o1.apply(this, arguments); };
    }
    if (window.CanvasRenderingContext2D) {
      var o2 = CanvasRenderingContext2D.prototype.getImageData;
      CanvasRenderingContext2D.prototype.getImageData = function(){ notify('canvas'); return o2.apply(this, arguments); };
    }
    var AC = window.AudioContext || window.webkitAudioContext;
    if (AC) {
      var o3 = AC.prototype.createAnalyser;
      AC.prototype.createAnalyser = function(){ notify('audio'); return o3.apply(this, arguments); };
    }
  } catch(e) {}
})();
"""

// ---------- Şifre kasası: Android Keystore ile AES-GCM ----------
private object PasswordVault {
    private const val ALIAS = "nexus_pw_key"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    fun encrypt(plain: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Pair(Base64.encodeToString(ct, Base64.NO_WRAP), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
    }

    fun decrypt(cipherTextB64: String, ivB64: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(Base64.decode(cipherTextB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }
}

private class TabInfo(val id: String, val private: Boolean, val webView: WebView) {
    var title: String = "Yeni sekme"
    var url: String? = null
}

class MainActivity : Activity() {

    private lateinit var topBar: WebView
    private lateinit var overlay: WebView
    private lateinit var contentContainer: FrameLayout

    private val tabs = LinkedHashMap<String, TabInfo>()
    private var activeTabId: String? = null
    private var tabCounter = 0
    private var splitSecondId: String? = null

    private val prefs by lazy { getSharedPreferences("nexus_prefs", MODE_PRIVATE) }
    private var privacyBlockedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        topBar = findViewById(R.id.topBarWebView)
        overlay = findViewById(R.id.overlayWebView)
        contentContainer = findViewById(R.id.contentContainer)

        setupChromeWebView(topBar, "file:///android_asset/top/index.html")
        setupChromeWebView(overlay, "file:///android_asset/overlay/index.html")
        // İlk sekme top/js/renderer.js tarafından AndroidHost.createTab(false) ile açılır.
    }

    private fun setupChromeWebView(wv: WebView, url: String) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(AndroidHost(), "AndroidHost")
        wv.webViewClient = WebViewClient()
        wv.setBackgroundColor(Color.WHITE)
        wv.loadUrl(url)
    }

    // ---------- Sekme oluşturma ----------
    private fun createTabInternal(isPrivate: Boolean): String {
        val id = "tab-" + (++tabCounter)
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        if (isPrivate) {
            wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            wv.settings.saveFormData = false
        }
        applyPerfSettings(wv)
        wv.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        wv.visibility = View.GONE

        val tab = TabInfo(id, isPrivate, wv)
        wv.webViewClient = buildContentWebViewClient(tab)
        wv.addJavascriptInterface(FingerprintBridge(), "NexusFp")

        tabs[id] = tab
        contentContainer.addView(wv)
        wv.loadUrl("file:///android_asset/home/index.html")

        showOnlyTab(id)
        return id
    }

    // ---------- Faz 3: Performans modları ----------
    private fun applyPerfSettings(wv: WebView) {
        val s = getSettingsObj()
        when (s.optString("perfMode", "dengeli")) {
            "veri" -> wv.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            "tasarruf" -> wv.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            else -> wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        }
    }

    private fun showOnlyTab(id: String) {
        activeTabId = id
        val sleepOn = getSettingsObj().optBoolean("tabSleep", false)
        tabs.values.forEach {
            val visible = it.id == id
            it.webView.visibility = if (visible) View.VISIBLE else View.GONE
            if (sleepOn) { if (visible) it.webView.onResume() else it.webView.onPause() } else it.webView.onResume()
        }
    }

    private fun buildContentWebViewClient(tab: TabInfo): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val httpsOnly = getSettingsObj().optBoolean("httpsOnly", false)
            if (httpsOnly && url.startsWith("http://")) {
                view.loadUrl(url.replaceFirst("http://", "https://"))
                return true
            }
            if (url.lowercase().endsWith(".pdf")) {
                try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(url), "application/pdf")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    return true
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "PDF açacak bir uygulama bulunamadı", Toast.LENGTH_SHORT).show() }
                }
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            try {
                if (!request.isForMainFrame) {
                    val s = getSettingsObj()
                    val blocking = s.optBoolean("adBlock", true) || s.optBoolean("trackerBlock", true)
                    val host = request.url.host ?: ""
                    if (blocking && TRACKER_DOMAINS.any { host == it || host.endsWith(".$it") }) {
                        privacyBlockedCount++
                        runOnUiThread { topBar.evaluateJavascript("window.NexusTop && NexusTop.onPrivacyUpdate($privacyBlockedCount)", null) }
                        return WebResourceResponse("text/plain", "utf-8", null)
                    }
                    val imgLimitMB = s.optInt("imageLimitMB", 0)
                    if (imgLimitMB > 0) {
                        val path = request.url.path ?: ""
                        val looksImage = IMAGE_EXT_REGEX.containsMatchIn(path) ||
                            (request.requestHeaders["Accept"]?.contains("image") == true)
                        if (looksImage) {
                            try {
                                val conn = URL(request.url.toString()).openConnection() as HttpURLConnection
                                conn.requestMethod = "HEAD"
                                conn.connectTimeout = 3000
                                conn.connect()
                                val len = conn.contentLengthLong
                                conn.disconnect()
                                if (len > imgLimitMB.toLong() * 1024 * 1024) {
                                    return WebResourceResponse("image/png", "utf-8", null)
                                }
                            } catch (e: Exception) { /* HEAD desteklenmiyorsa sınırlama atlanır */ }
                        }
                    }
                }
            } catch (e: Exception) { }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view.evaluateJavascript(FINGERPRINT_JS, null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            tab.url = url
            tab.title = view.title ?: url ?: "Yeni sekme"
            if (!tab.private && url != null && !url.startsWith("file:///android_asset/home")) {
                addHistoryEntry(tab.title, url)
            }
            pushNavStateToTop(tab, view)
            Thread { trimCacheIfNeeded() }.start()
        }
    }

    private fun pushNavStateToTop(tab: TabInfo, view: WebView) {
        val displayUrl = if (tab.url?.startsWith("file:///android_asset/home") == true) "" else (tab.url ?: "")
        val js = "window.NexusTop && NexusTop.onNavigated(" +
            "${jsStr(tab.id)}, ${jsStr(displayUrl)}, ${jsStr(tab.title)}, ${view.canGoBack()}, ${view.canGoForward()})"
        runOnUiThread { topBar.evaluateJavascript(js, null) }
    }

    private fun jsStr(s: String): String = JSONObject.quote(s)

    // ---------- Ayarlar ----------
    private fun getSettingsObj(): JSONObject {
        val raw = prefs.getString("settings", null)
        return if (raw != null) JSONObject(raw) else JSONObject().apply {
            put("adBlock", true); put("trackerBlock", true); put("httpsOnly", false)
            put("perfMode", "dengeli"); put("tabSleep", false)
            put("imageLimitMB", 0); put("cacheLimitMB", 0)
            put("accentColor", "#5b5bf0")
            put("groqApiKey", ""); put("groqModel", "llama-3.3-70b-versatile")
            put("updateCheckUrl", "")
        }
    }
    private fun saveSettingsObj(obj: JSONObject) { prefs.edit().putString("settings", obj.toString()).commit() }

    // ---------- Geçmiş ----------
    private fun addHistoryEntry(title: String, url: String) {
        val arr = getHistoryArr()
        val entry = JSONObject().apply { put("title", title); put("url", url); put("time", System.currentTimeMillis()) }
        val newArr = JSONArray()
        newArr.put(entry)
        for (i in 0 until minOf(arr.length(), 400)) newArr.put(arr.get(i))
        prefs.edit().putString("history", newArr.toString()).commit()
    }
    private fun getHistoryArr(): JSONArray {
        val raw = prefs.getString("history", null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    // ---------- Yer imleri ----------
    private fun getBookmarksArr(): JSONArray {
        val raw = prefs.getString("bookmarks", null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }
    private fun saveBookmarksArr(arr: JSONArray) { prefs.edit().putString("bookmarks", arr.toString()).commit() }

    // ---------- Şifreler ----------
    private fun getPasswordsArr(): JSONArray {
        val raw = prefs.getString("passwords", null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }
    private fun savePasswordsArr(arr: JSONArray) { prefs.edit().putString("passwords", arr.toString()).commit() }

    // ---------- Faz 3: Önbellek sınırı ----------
    private fun getDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { f -> size += if (f.isDirectory) getDirSize(f) else f.length() }
        return size
    }
    private fun trimCacheIfNeeded() {
        val limitMB = getSettingsObj().optInt("cacheLimitMB", 0)
        if (limitMB <= 0) return
        try {
            if (getDirSize(cacheDir) > limitMB.toLong() * 1024 * 1024) {
                runOnUiThread { tabs.values.forEach { it.webView.clearCache(false) } }
            }
        } catch (e: Exception) { }
    }

    // ---------- Faz 3/4: Groq AI çağrısı (özet + sekme gruplama için ortak) ----------
    private fun callGroq(apiKey: String, model: String, systemPrompt: String, userPrompt: String): String {
        return try {
            val conn = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
                })
                put("temperature", 0.3)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) return "HATA ($code): $text"
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) { "İstek başarısız: ${e.message}" }
    }

    // ---------- Faz 4: Sızıntı kontrolü (HIBP k-anonymity, anahtarsız) ----------
    private fun isPasswordBreached(password: String): Boolean {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
        val prefix = sha1.substring(0, 5)
        val suffix = sha1.substring(5)
        val conn = URL("https://api.pwnedpasswords.com/range/$prefix").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return text.lineSequence().any { it.startsWith(suffix, ignoreCase = true) }
    }

    private inner class FingerprintBridge {
        private var warned = false
        @JavascriptInterface
        fun report(type: String) {
            if (warned) return
            warned = true
            runOnUiThread {
                val label = if (type == "audio") "ses (audio)" else "canvas"
                Toast.makeText(this@MainActivity, "Bu site $label tabanlı parmak izi tespiti yapmaya çalıştı.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class AndroidHost {

        // ---- Sekmeler ----
        @JavascriptInterface
        fun createTab(isPrivate: Boolean): String {
            var id = ""
            runOnUiThread { id = createTabInternal(isPrivate) }
            var waited = 0
            while (id == "" && waited < 200) { Thread.sleep(5); waited++ }
            return id
        }

        @JavascriptInterface
        fun closeTab(tabId: String) {
            runOnUiThread {
                val tab = tabs.remove(tabId) ?: return@runOnUiThread
                contentContainer.removeView(tab.webView)
                tab.webView.destroy()
                if (tabs.isEmpty()) createTabInternal(false)
                else if (activeTabId == tabId) {
                    val lastId = tabs.keys.last()
                    showOnlyTab(lastId)
                    tabs[lastId]?.let { pushNavStateToTop(it, it.webView) }
                }
            }
        }

        @JavascriptInterface
        fun switchTab(tabId: String) {
            runOnUiThread {
                if (tabs.containsKey(tabId)) {
                    showOnlyTab(tabId)
                    tabs[tabId]?.let { pushNavStateToTop(it, it.webView) }
                }
            }
        }

        @JavascriptInterface
        fun navigate(tabId: String, url: String) { runOnUiThread { tabs[tabId]?.webView?.loadUrl(url) } }

        @JavascriptInterface
        fun goBack(tabId: String) { runOnUiThread { tabs[tabId]?.webView?.let { if (it.canGoBack()) it.goBack() } } }

        @JavascriptInterface
        fun goForward(tabId: String) { runOnUiThread { tabs[tabId]?.webView?.let { if (it.canGoForward()) it.goForward() } } }

        @JavascriptInterface
        fun reload(tabId: String) { runOnUiThread { tabs[tabId]?.webView?.reload() } }

        // ---- Paneller ----
        @JavascriptInterface
        fun showPanel(name: String) {
            runOnUiThread {
                overlay.evaluateJavascript("window.NexusOverlay && NexusOverlay.open(${jsStr(name)})", null)
                overlay.visibility = View.VISIBLE
            }
        }
        @JavascriptInterface
        fun closePanel() { runOnUiThread { overlay.visibility = View.GONE } }

        // ---- Geçmiş ----
        @JavascriptInterface
        fun getHistoryJson(): String = getHistoryArr().toString()
        @JavascriptInterface
        fun clearHistory() { prefs.edit().remove("history").commit() }

        // ---- Yer imleri ----
        @JavascriptInterface
        fun getBookmarksJson(): String = getBookmarksArr().toString()
        @JavascriptInterface
        fun toggleBookmark(title: String, url: String): Boolean {
            val arr = getBookmarksArr()
            var idx = -1
            for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("url") == url) { idx = i; break }
            return if (idx >= 0) {
                val n = JSONArray(); for (i in 0 until arr.length()) if (i != idx) n.put(arr.get(i))
                saveBookmarksArr(n); false
            } else {
                arr.put(JSONObject().apply { put("title", title); put("url", url) }); saveBookmarksArr(arr); true
            }
        }
        @JavascriptInterface
        fun isBookmarked(url: String): Boolean {
            val arr = getBookmarksArr()
            for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("url") == url) return true
            return false
        }

        // ---- Ayarlar (birleşik: value JSON string olarak gelir) ----
        @JavascriptInterface
        fun getSettingsJson(): String = getSettingsObj().toString()
        @JavascriptInterface
        fun setSetting(key: String, jsonValue: String) {
            val obj = getSettingsObj()
            obj.put(key, JSONTokener(jsonValue).nextValue())
            saveSettingsObj(obj)
            if (key == "perfMode" || key == "tabSleep") {
                runOnUiThread { tabs.values.forEach { applyPerfSettings(it.webView) } }
            }
            if (key == "accentColor") {
                val hex = obj.optString("accentColor", "#5b5bf0")
                val setCss = "document.documentElement.style.setProperty('--accent', ${jsStr(hex)})"
                runOnUiThread { topBar.evaluateJavascript(setCss, null); overlay.evaluateJavascript(setCss, null) }
            }
        }

        // ---- Şifreler ----
        @JavascriptInterface
        fun getPasswordsJson(): String {
            val arr = getPasswordsArr()
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val plain = PasswordVault.decrypt(o.optString("secret"), o.optString("iv"))
                out.put(JSONObject().apply {
                    put("id", o.optString("id")); put("site", o.optString("site"))
                    put("username", o.optString("username")); put("password", plain)
                })
            }
            return out.toString()
        }
        @JavascriptInterface
        fun savePassword(site: String, username: String, password: String) {
            val pair = PasswordVault.encrypt(password)
            val arr = getPasswordsArr()
            arr.put(JSONObject().apply {
                put("id", "pw-" + UUID.randomUUID().toString().take(8))
                put("site", site); put("username", username); put("secret", pair.first); put("iv", pair.second)
            })
            savePasswordsArr(arr)
        }
        @JavascriptInterface
        fun deletePassword(id: String) {
            val arr = getPasswordsArr()
            val n = JSONArray(); for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") != id) n.put(arr.get(i))
            savePasswordsArr(n)
        }

        // ---- Faz 4: Sızıntı kontrolü ----
        @JavascriptInterface
        fun checkBreachesAsync() {
            Thread {
                val arr = getPasswordsArr()
                val results = JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val plain = PasswordVault.decrypt(o.optString("secret"), o.optString("iv"))
                    val breached = try { isPasswordBreached(plain) } catch (e: Exception) { null }
                    results.put(JSONObject().apply {
                        put("id", o.optString("id")); put("site", o.optString("site"))
                        put("breached", breached ?: JSONObject.NULL)
                    })
                }
                runOnUiThread { overlay.evaluateJavascript("window.NexusOverlay && NexusOverlay.onBreachResults(${jsStr(results.toString())})", null) }
            }.start()
        }

        // ---- Gizlilik panosu ----
        @JavascriptInterface
        fun getPrivacyCount(): Int = privacyBlockedCount

        // ---- Faz 3: Akıllı arama önerileri (yerel, AI'sız) ----
        @JavascriptInterface
        fun getSmartSuggestionsJson(query: String): String {
            if (query.isBlank()) return "[]"
            val q = query.lowercase()
            val out = JSONArray()
            val hist = getHistoryArr()
            for (i in 0 until hist.length()) {
                if (out.length() >= 4) break
                val o = hist.getJSONObject(i)
                if (o.optString("title").lowercase().contains(q) || o.optString("url").lowercase().contains(q)) out.put(o)
            }
            val bm = getBookmarksArr()
            for (i in 0 until bm.length()) {
                if (out.length() >= 6) break
                val o = bm.getJSONObject(i)
                if (o.optString("title").lowercase().contains(q) || o.optString("url").lowercase().contains(q)) out.put(o)
            }
            return out.toString()
        }

        // ---- Faz 3: AI sekme gruplama ----
        @JavascriptInterface
        fun groupTabsAI(tabsJson: String) {
            val s = getSettingsObj()
            val apiKey = s.optString("groqApiKey", "")
            if (apiKey.isBlank()) {
                runOnUiThread { topBar.evaluateJavascript("window.NexusTop && NexusTop.onGroupingResult(${jsStr("NOKEY")})", null) }
                return
            }
            val model = s.optString("groqModel", "llama-3.3-70b-versatile")
            Thread {
                val sys = "Sekme başlıklarını/URL'lerini konularına göre grupla. SADECE geçerli JSON döndür, başka hiçbir açıklama ekleme: " +
                    "{\"gruplar\":[{\"isim\":\"kısa grup adı\",\"sekme_id\":[\"tab-1\",\"tab-2\"]}]}"
                val result = callGroq(apiKey, model, sys, tabsJson)
                runOnUiThread { overlay.evaluateJavascript("window.NexusOverlay && NexusOverlay.onGroupingResult(${jsStr(result)})", null) }
            }.start()
        }

        // ---- Aktif sekme id'si (overlay panelinin hangi sekmede işlem yapacağını bilmesi için) ----
        @JavascriptInterface
        fun getActiveTabId(): String = activeTabId ?: ""

        // ---- Faz 4: AI sayfa özetleme ----
        @JavascriptInterface
        fun aiSummarizePage(tabId: String) {
            runOnUiThread {
                val tab = tabs[tabId] ?: return@runOnUiThread
                tab.webView.evaluateJavascript(
                    "(function(){try{return document.body ? document.body.innerText.slice(0,4000) : '';}catch(e){return '';}})()"
                ) { rawResult ->
                    val pageText = try { (JSONTokener(rawResult).nextValue() as? String) ?: "" } catch (e: Exception) { "" }
                    val s = getSettingsObj()
                    val apiKey = s.optString("groqApiKey", "")
                    if (apiKey.isBlank()) {
                        overlay.evaluateJavascript("window.NexusOverlay && NexusOverlay.onSummaryResult(${jsStr("Groq API anahtarı ayarlanmamış. Menü > Ayarlar'dan ekleyin.")})", null)
                        return@evaluateJavascript
                    }
                    val model = s.optString("groqModel", "llama-3.3-70b-versatile")
                    val isHttps = tab.url?.startsWith("https://") == true
                    val urlSnapshot = tab.url ?: ""
                    val titleSnapshot = tab.title
                    Thread {
                        val sys = "Sen bir web güvenliği ve gizlilik analistisin. Kullanıcıya Türkçe, kısa ve maddeler halinde: " +
                            "1) Sitenin güvenilirliği, 2) Gizlilik/veri toplama riski, 3) Dolandırıcılık/kimlik avı belirtisi olup olmadığı, " +
                            "4) Bilinen şikayet/kötüye kullanım örüntülerine dair GENEL bilgin varsa belirt, canlı arama yapamadığını da söyle."
                        val usr = "Site: $urlSnapshot\nHTTPS: $isHttps\nBaşlık: $titleSnapshot\nSayfa metni (kısaltılmış):\n$pageText"
                        val result = callGroq(apiKey, model, sys, usr)
                        runOnUiThread { overlay.evaluateJavascript("window.NexusOverlay && NexusOverlay.onSummaryResult(${jsStr(result)})", null) }
                    }.start()
                }
            }
        }

        // ---- Faz 4: Ekran görüntüsü (site JS'inin engellemesini by-pass eder) ----
        @JavascriptInterface
        fun takeScreenshot(tabId: String) {
            runOnUiThread {
                val wv = tabs[tabId]?.webView ?: return@runOnUiThread
                if (wv.width == 0 || wv.height == 0) return@runOnUiThread
                val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
                wv.draw(Canvas(bmp))
                Thread {
                    try {
                        val filename = "Nexus_" + System.currentTimeMillis() + ".png"
                        val values = android.content.ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NexusBrowser")
                        }
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                            runOnUiThread { Toast.makeText(this@MainActivity, "Ekran görüntüsü kaydedildi (Resimler/NexusBrowser)", Toast.LENGTH_SHORT).show() }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Ekran görüntüsü kaydedilemedi: ${e.message}", Toast.LENGTH_SHORT).show() }
                    }
                }.start()
            }
        }

        // ---- Faz 4: PDF olarak kaydet (Android'in resmi Yazdır sistemi ile) ----
        @JavascriptInterface
        fun printPageAsPdf(tabId: String) {
            runOnUiThread {
                val tab = tabs[tabId] ?: return@runOnUiThread
                val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val jobName = "Nexus_" + tab.title.replace(Regex("[^A-Za-z0-9]+"), "_").take(40)
                val adapter = tab.webView.createPrintDocumentAdapter(jobName)
                printManager.print(jobName, adapter, PrintAttributes.Builder().build())
            }
        }

        // ---- Faz 4: Bölünmüş ekran ----
        @JavascriptInterface
        fun enterSplitView(secondTabId: String) {
            runOnUiThread {
                val activeId = activeTabId ?: return@runOnUiThread
                val topTab = tabs[activeId] ?: return@runOnUiThread
                val bottomTab = tabs[secondTabId] ?: return@runOnUiThread
                splitSecondId = secondTabId
                contentContainer.post {
                    val h = contentContainer.height / 2
                    if (h <= 0) return@post
                    tabs.values.forEach { it.webView.visibility = View.GONE }
                    topTab.webView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h)
                    bottomTab.webView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h).apply { topMargin = h }
                    topTab.webView.visibility = View.VISIBLE
                    bottomTab.webView.visibility = View.VISIBLE
                }
            }
        }
        @JavascriptInterface
        fun exitSplitView() {
            runOnUiThread {
                splitSecondId = null
                tabs.values.forEach { it.webView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT) }
                activeTabId?.let { showOnlyTab(it) }
            }
        }
        @JavascriptInterface
        fun getOpenTabsJson(): String {
            val out = JSONArray()
            tabs.values.forEach { out.put(JSONObject().apply { put("id", it.id); put("title", it.title) }) }
            return out.toString()
        }

        // ---- Faz 4: Otomatik güncelleme kontrolü ----
        @JavascriptInterface
        fun checkForUpdate() {
            val url = getSettingsObj().optString("updateCheckUrl", "")
            if (url.isBlank()) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Güncelleme adresi ayarlanmamış (Ayarlar'dan ekleyin)", Toast.LENGTH_SHORT).show() }
                return
            }
            Thread {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val obj = JSONObject(text)
                    val remoteVersion = obj.optInt("versionCode", 0)
                    val apkUrl = obj.optString("apkUrl", "")
                    runOnUiThread {
                        if (remoteVersion > APP_VERSION_CODE && apkUrl.isNotBlank()) {
                            Toast.makeText(this@MainActivity, "Yeni sürüm bulundu, indiriliyor. Bildirime dokunarak kurun.", Toast.LENGTH_LONG).show()
                            val request = DownloadManager.Request(Uri.parse(apkUrl))
                                .setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, "nexus-update.apk")
                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                        } else {
                            Toast.makeText(this@MainActivity, "Güncel sürümü kullanıyorsunuz", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Güncelleme kontrolü başarısız: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }
    }
}
