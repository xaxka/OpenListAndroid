package com.xaxka.openlist.ui.web

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.xaxka.openlist.ui.components.FlatLinearProgress
import com.xaxka.openlist.ui.components.SnackAction
import com.xaxka.openlist.ui.components.SnackBarHost
import com.xaxka.openlist.ui.components.SnackBarState
import com.xaxka.openlist.ui.components.SnackData
import com.xaxka.openlist.ui.theme.OpenListSurface
import com.xaxka.openlist.ui.theme.WebDarkProgressTrack
import com.xaxka.openlist.ui.theme.WebDarkSurface
import com.xaxka.openlist.ui.theme.WebProgressTrack
import java.net.URLDecoder

/** WebView 回调集合（WebScreen 组装，client 闭包持有） */
private class WebCallbacks(
    val onExternalScheme: (Uri) -> Unit,
    val onDownloadRequested: (url: String, fileName: String?) -> Unit,
    val onLoadError: () -> Unit,
    val onPageStarted: () -> Unit,
    val onPageFinished: () -> Unit,
    val onProgressChanged: (Int) -> Unit,
    val onCanGoBackChanged: (Boolean) -> Unit,
)

/** scheme 白名单（照源 web.dart:96-104） */
private val ALLOWED_SCHEMES = setOf("http", "https", "file", "chrome", "data", "javascript", "about")

/**
 * Web 页（照源 tmp/lib/pages/web/web.dart）：
 * 顶部 4dp 进度条 + WebView；返回键先回退网页历史；外部 scheme 交系统；
 * 下载请求弹贴底确认条；加载失败自动拉起服务并重载。
 *
 * 网页区域跟随系统深色：App 外壳固定浅色（Blue Light 原则 5），但网页内容按系统
 * 深色渲染（33+ 算法变暗 / 29-32 force-dark），顶部状态栏占位条同步深色，
 * 避免深色网页顶部出现白色横条。
 */
@Composable
fun WebScreen(
    modifier: Modifier = Modifier,
    viewModel: WebViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackState = remember { SnackBarState() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    val darkTheme = isSystemInDarkTheme()
    val chromeColor = if (darkTheme) WebDarkSurface else MaterialTheme.colorScheme.surface

    // 状态栏图标外观：深色顶条上切浅色图标；离开 Web 页恢复 App 固定浅色样式
    val view = LocalView.current
    DisposableEffect(view, darkTheme) {
        val controller = view.context.findActivity()?.window
            ?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !darkTheme
        onDispose { controller?.isAppearanceLightStatusBars = true }
    }

    // 端口发现 / 自愈成功 / 再点当前 tab → 重载
    LaunchedEffect(viewModel) {
        viewModel.reloadEvents.collect { url -> webView?.loadUrl(url) }
    }

    // 离开组合时销毁 WebView
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    // 返回键拦截：可后退时先回退网页历史，否则交回导航栈（照源 web.dart:64-70 PopScope）
    BackHandler(enabled = canGoBack) { webView?.goBack() }

    val callbacks = WebCallbacks(
        onExternalScheme = { uri ->
            if (viewModel.silentJumpApp.value) {
                // 静默模式：直接拉起系统，失败吞掉（照源 NativeCommon.startActivityFromUri）
                tryStartActivityForUri(context, uri)
            } else {
                snackState.show(
                    SnackData(
                        message = "跳转到其他APP ？",
                        durationMs = 5000L,
                        actions = listOf(
                            SnackAction(label = "前往") { tryStartActivityForUri(context, uri) },
                        ),
                    )
                )
            }
        },
        onDownloadRequested = { url, fileName ->
            snackState.show(
                SnackData(
                    title = "下载此文件吗？",
                    message = fileName ?: url,
                    durationMs = 3000L,
                    actions = listOf(
                        SnackAction(label = "选择应用打开") { launchChooserForUrl(context, url, "选择应用打开") },
                        SnackAction(label = "下载") { enqueueWebDownload(context, url, fileName) },
                    ),
                    onTap = {
                        clipboard.setText(AnnotatedString(url))
                        snackState.show(SnackData(message = "已复制到剪贴板", durationMs = 1000L))
                    },
                )
            )
        },
        onLoadError = viewModel::recoverFromLoadError,
        onPageStarted = { progress = 0f },
        onPageFinished = { progress = 0f },
        onProgressChanged = { p -> progress = if (p >= 100) 0f else p / 100f },
        onCanGoBackChanged = { canGoBack = it },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(chromeColor),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 状态栏等高占位（照源 web.dart:73 SizedBox(height: padding.top)）；
            // 顶部 inset 仅此一处——外层 Scaffold 已关闭 contentWindowInsets，不再叠加。
            // 占位区露出 Box 底色：深色网页时同步深色，浅色时为 surface
            Spacer(Modifier.fillMaxWidth().statusBarsPadding())
            // 4dp 线性进度条（完成即归零隐藏）
            FlatLinearProgress(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
                trackColor = if (darkTheme) WebDarkProgressTrack else WebProgressTrack,
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    createWebView(
                        context = ctx,
                        initialUrl = viewModel.urlToLoad.value,
                        callbacks = callbacks,
                        darkTheme = darkTheme,
                    ).also { webView = it }
                },
                // MainActivity 声明 uiMode 自处理（不重建 Activity），深浅切换时同步 WebView
                update = { it.applyRenderTheme(darkTheme) },
            )
        }
        SnackBarHost(state = snackState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** 创建 WebView：JS/DOM storage/免手势媒体播放照源 InAppWebViewSettings */
@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    initialUrl: String,
    callbacks: WebCallbacks,
    darkTheme: Boolean,
): WebView =
    WebView(context).apply {
        // Compose AndroidView 首帧测量时序：显式 MATCH_PARENT 测量意图，
        // 避免 WebView 停留在首帧小视口（表现为网页只占屏幕一部分）
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        // 网页跟随系统深色（含加载过程中的底色，避免白屏闪烁）
        applyRenderTheme(darkTheme)
        // SPA（rem/vw 适配）可能在视口未稳定时按错误基准布局，视图尺寸有效后补发 resize 触发重排
        attachViewportReflow()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase() ?: return false
                if (scheme in ALLOWED_SCHEMES) return false
                // 外部 scheme（mailto/tel/intent/market/第三方私有协议）一律取消 WebView 内导航
                callbacks.onExternalScheme(request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                callbacks.onPageStarted()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                callbacks.onPageFinished()
                callbacks.onCanGoBackChanged(view.canGoBack())
                // 覆盖「先加载完成、后视图才放大」的时序：加载完成后补发一次 resize
                view.post { view.evaluateJavascript(REFLOW_SCRIPT, null) }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // 仅主文档失败触发自愈（照源语义；本回调 API23+，更低版本走下方旧回调）
                if (request.isForMainFrame) {
                    callbacks.onLoadError()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                // API 21-22：旧回调仅主资源
                callbacks.onLoadError()
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                callbacks.onProgressChanged(newProgress)
                callbacks.onCanGoBackChanged(view.canGoBack())
            }
        }
        setDownloadListener(
            DownloadListener { url, _, contentDisposition, _, _ ->
                callbacks.onDownloadRequested(url, parseSuggestedFileName(contentDisposition, url))
            }
        )
        loadUrl(initialUrl)
    }

/** 触发页面 resize 监听重排的最小脚本（复用页面自身 resize 适配逻辑，不注入具体实现） */
private const val REFLOW_SCRIPT =
    "(function(){try{window.dispatchEvent(new Event('resize'))}catch(e){}})()"

/** 首次布局出现有效宽高时补发一次 resize，随后移除监听（只执行一次） */
private fun WebView.attachViewportReflow() {
    addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
        ) {
            if (right - left > 0 && bottom - top > 0) {
                removeOnLayoutChangeListener(this)
                post { evaluateJavascript(REFLOW_SCRIPT, null) }
            }
        }
    })
}

/**
 * 网页渲染主题（App uiMode 跟随系统）：
 * - API 33+：算法变暗开关。允许后 App 处于深色时 WebView 以深色渲染，
 *   页面 prefers-color-scheme 返回 dark（OpenList Web 按系统出暗色主题）；
 * - API 29-32：旧 force-dark 自动档（同为跟随 App 深色）；
 * - API 21-28：WebView 无深色支持，保持浅色（顶条同步浅色，不产生割裂）。
 * 同时设置 WebView 底色，深色下加载过程中不再闪白。
 */
@Suppress("DEPRECATION") // API 29-32 走旧 force-dark（33+ 已弃用，由算法变暗分支承接）
private fun WebView.applyRenderTheme(darkTheme: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        settings.isAlgorithmicDarkeningAllowed = darkTheme
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        settings.forceDark =
            if (darkTheme) WebSettings.FORCE_DARK_AUTO else WebSettings.FORCE_DARK_OFF
    }
    setBackgroundColor((if (darkTheme) WebDarkSurface else OpenListSurface).toArgb())
}

/** 沿 ContextWrapper 链解包出 Activity（Compose 视图 context 通常是包装过的） */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 从 Content-Disposition 解析建议文件名（等价源 suggestedFilename/contentDisposition 回退链） */
private fun parseSuggestedFileName(contentDisposition: String?, url: String): String? {
    if (!contentDisposition.isNullOrEmpty()) {
        FilenameStarPattern.find(contentDisposition)?.groupValues?.get(1)?.let { encoded ->
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
        }
        FilenamePlainPattern.find(contentDisposition)?.groupValues?.get(1)?.let { return it }
        return contentDisposition
    }
    return url.substringAfterLast('/', "").takeIf { it.isNotEmpty() }
}

private val FilenameStarPattern =
    Regex("""filename\*\s*=\s*(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
private val FilenamePlainPattern =
    Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)

/** 静默拉起外部 scheme：try startActivity，失败吞掉 */
private fun tryStartActivityForUri(context: Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

/** ACTION_VIEW + 系统选择器（照源 launchChooser） */
private fun launchChooserForUrl(context: Context, url: String, title: String) {
    try {
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(base, title))
    } catch (_: Exception) {
    }
}

/** 系统下载服务入队（Web 页「下载」按钮，系统默认目录 + 完成通知） */
private fun enqueueWebDownload(context: Context, url: String, fileName: String?) {
    try {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (!fileName.isNullOrEmpty()) setTitle(fileName)
        }
        manager.enqueue(request)
    } catch (_: Exception) {
    }
}
