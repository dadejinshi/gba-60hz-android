package com.gba60hz.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream

/**
 * GBA 60Hz 原生壳。
 *
 * 设计原则：内核（WASM）与全部 UI（HTML/CSS/JS）原封不动复用，
 * MainActivity 只做两件事：
 *  1) 用 WebViewAssetLoader 把 assets/GBA-60Hz.html 映射到 https origin，
 *     保证 IndexedDB / WebAudio / WebGL 正常工作（file:// 下会失效）；
 *  2) 通过 WebChromeClient / JavascriptInterface 补齐 WebView 缺失的原生能力
 *     （文件选择、分享、震动、全屏、返回键、外部 ROM 打开）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout

    // 文件选择回调（ROM / 存档 / 金手指导入共用）
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // 全屏.custom view（横屏全屏游戏）
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // WebViewAssetLoader：把 /assets/ 映射到安全 origin
    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(this))
            .build()
    }

    // SAF：多选文件（.gba / .zip / .sav / .json）
    private val openDocumentsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            deliverFileResult(uris)
        }

    // SAF：选择文件夹（整目录导入）
    private val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            filePathCallback?.onReceiveValue(if (uri == null) null else arrayOf(uri))
            filePathCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(webView)

        configureWebView()
        webView.loadUrl(APP_URL)

        // 返回键：先交给页面（关菜单），页面不消费才退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitCustomView()
                    else -> webView.evaluateJavascript(Js.GO_BACK) { result ->
                        if (result != "true") finish()
                    }
                }
            }
        })

        // 外部"用本 App 打开 .gba"
        handleIncomingIntent(intent)
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // IndexedDB 必需
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false  // 让 WebAudio 无需额外手势即可恢复
            setSupportZoom(false)
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false           // 走 AssetLoader，不用 file://
            allowContentAccess = true         // 允许读取 SAF content:// Uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }
        // 硬件加速提升 WebGL / 合成性能
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // JS -> 原生桥接
        webView.addJavascriptInterface(GbaBridge(this), BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? = request?.let { assetLoader.shouldInterceptRequest(it.url) }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return when {
                    url.startsWith(APP_ORIGIN) -> false
                    url.startsWith("blob:") || url.startsWith("data:") -> false
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        // 外部链接交给系统浏览器，不劫持
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            Log.w(TAG, "打开外链失败", e)
                        }
                        true
                    }
                    else -> true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 注入增强脚本（返回键钩子、blob 下载接管、外部 ROM 注入）
                view?.evaluateJavascript(Js.INJECT, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            /** 文件选择：把 WebView 的回调转接给 Android SAF */
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val accepts = fileChooserParams?.acceptTypes ?: emptyArray()
                val isDirectory = accepts.isEmpty() ||
                        accepts.any { it.equals("webkitdirectory", ignoreCase = true) }

                if (isDirectory) {
                    openTreeLauncher.launch(null)
                } else {
                    // 全部用 */*，扩展名由前端 <input accept> 与后续解析负责
                    openDocumentsLauncher.launch(arrayOf("*/*"))
                }
                return true
            }

            /** 全屏：Video/全屏 API 触发，用一个覆盖层承载 */
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                root.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                webView.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            override fun onHideCustomView() = exitCustomView()

            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                cm?.let { Log.d("GBA-JS", "${it.message()} @${it.sourceId()}:${it.lineNumber()}") }
                return true
            }
        }

        // 截图下载：blob → 原生保存（注入脚本会把 <a download> 转成桥接调用）
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            Toast.makeText(this, "正在保存文件…", Toast.LENGTH_SHORT).show()
            GbaBridge(this).downloadUrl(url, contentDisposition, mimeType)
        }
    }

    private fun deliverFileResult(uris: List<Uri>) {
        val cb = filePathCallback
        filePathCallback = null
        if (uris.isEmpty()) {
            cb?.onReceiveValue(null)
            return
        }
        // 授予持久读权限，避免 App 重启后 URI 失效
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
            }
        }
        cb?.onReceiveValue(uris.toTypedArray())
    }

    private fun exitCustomView() {
        customView?.let {
            root.removeView(it)
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            webView.visibility = View.VISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val name = queryDisplayName(uri) ?: "外部游戏.gba"
            webView.evaluateJavascript(
                "window.GbaNativeOpenExternal && " +
                        "GbaNativeOpenExternal('$b64', '${escapeJs(name)}')",
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "读取外部 ROM 失败", e)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun escapeJs(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    override fun onPause() {
        super.onPause()
        // 切后台触发页面内的自动存档逻辑（页面自身也有 visibilitychange 兜底）
        webView.evaluateJavascript(Js.PAUSE, null)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GBA60Hz"
        const val BRIDGE_NAME = "GbaNative"
        const val APP_ORIGIN = "https://appassets.androidplatform.net"
        const val APP_URL = "$APP_ORIGIN/assets/GBA-60Hz.html"
    }
}

/** 注入到页面里的 JS 片段（不改动 app.html 源码；桥接对象名保持与 BRIDGE_NAME 一致：GbaNative） */
private object Js {
    /** 返回键：若设置页/弹窗打开则关闭它们并消费返回键 */
    val GO_BACK = """
        (function(){
          try {
            var m = document.getElementById('modal');
            if (m && m.classList.contains('on')) { if (window.closeSheet) closeSheet(); return true; }
            var s = document.getElementById('settingsPage');
            if (s && s.classList.contains('on')) { if (window.closeSettings) closeSettings(); return true; }
          } catch(e){}
          return false;
        })()
    """.trimIndent()

    /** 切后台：触发自动存档（函数存在才调用） */
    val PAUSE = """
        (function(){ try { if (window.autoSave) autoSave(true); } catch(e){} })()
    """.trimIndent()

    /** 页面加载完成后注入的增强。桥接名写死为 GbaNative。 */
    val INJECT = """
        (function(){
          var N = window.GbaNative;

          // 1) 接管 <a download> 的 blob 下载，改走原生保存，避免 WebView 下载管理器的兼容问题
          try {
            var origClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function(){
              if (this.download && this.href && this.href.indexOf('blob:') === 0) {
                var self = this;
                fetch(this.href).then(function(r){ return r.blob(); }).then(function(b){
                  var fr = new FileReader();
                  fr.onload = function(){
                    if (N && N.saveData) N.saveData(String(fr.result), self.download || 'gba-shot.png');
                  };
                  fr.readAsDataURL(b);
                }).catch(function(){ origClick.call(self); });
                return;
              }
              return origClick.apply(this, arguments);
            };
          } catch(e){}

          // 2) 外部打开 .gba：把字节交给页面现有的 loadRom
          window.GbaNativeOpenExternal = function(b64, name){
            try {
              var bin = atob(b64), u = new Uint8Array(bin.length);
              for (var i=0;i<bin.length;i++) u[i] = bin.charCodeAt(i);
              if (typeof loadRom === 'function') loadRom(u, name, 'ext-' + u.length + '-' + Date.now());
            } catch(e) {}
          };

          // 3) 让分享优先走原生（页面 navigator.share 在 WebView 常不可用）
          if (N && !navigator.share) {
            navigator.share = function(data){
              var text = (data && data.text) || (data && data.title) || '';
              if (N.shareText) N.shareText(String(text));
              return Promise.resolve();
            };
          }
        })()
    """.trimIndent()
}
