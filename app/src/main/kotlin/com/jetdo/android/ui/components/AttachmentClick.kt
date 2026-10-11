package com.jetdo.android.ui.components

import androidx.compose.ui.res.stringResource
import com.jetdo.android.R
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.jetdo.android.services.content.AttachmentOpener
import com.jetdo.android.services.network.ArkWebNetworkBridge
import kotlinx.coroutines.launch
import java.io.File

/**
 * 附件（`<a class="attachment">`）点击后的处理入口。
 *
 * 用 CompositionLocal 而不是逐层加参数：从 `PostRichContent` 到真正渲染行内文本的
 * `InlineText` 之间隔着 10 来个 composable（Quote / Callout / List / Spoiler / Disclosure …），
 * 每个都已背着 `onLinkClick`、`onResolveMedia`、`onOpenImages` 三个回调 —— 再加一个等于
 * 把 10 个签名和 20 多个调用点全改一遍，纯机械噪音且极易漏。
 *
 * 默认 null = 没人在意附件点击，此时回落到普通的 `onLinkClick`（保持既有行为不变）。
 */
internal val LocalAttachmentClick = compositionLocalOf<((String, String) -> Unit)?> { null }

/**
 * 「下载 → 交给系统打开」的默认实现，供无人显式接管附件的页面直接用。
 *
 * 走的是既有那座过 CF 的桥（`ArkWebNetworkBridge.downloadAttachment`，与音视频同一条路：
 * host webview 的下载委托 + 会话 Cookie），下到 `getExternalFilesDir()/download` 再交系统面板。
 * 论坛是同源、直连会被 CF 挡，所以**不能**用 OkHttp/DownloadManager 直连。
 */
@Composable
internal fun rememberAttachmentOpener(): (String, String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context) {
        { url: String, name: String ->
            if (url.isNotEmpty()) {
                val label = name.ifEmpty { context.getString(R.string.composer_kind_file) }
                Toast.makeText(context, context.getString(R.string.common_downloading, label), Toast.LENGTH_SHORT).show()
                scope.launch {
                    // 这里**不能**套 Dispatchers.IO：整条链路是挂起式的（等系统广播结算），
                    // 真正的 IO 在 WebView/DownloadManager 那边；而途中的 loadUrl 是 WebView 调用、
                    // Toast/startActivity 都是 UI 操作，全都要在主线程上跑。
                    val path = runCatching { ArkWebNetworkBridge.downloadAttachment(url) }.getOrNull()
                    val file = path?.let { File(it) }
                    if (file == null || !file.exists() || file.length() == 0L) {
                        Toast.makeText(context, context.getString(R.string.common_download_failed_retry), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    // 类型按**落盘名**推断：服务端在重名时会加后缀，落盘名才是真身；
                    // 链接文字（name）只用于提示文案。
                    val opened = AttachmentOpener.openWithSystem(context, file)
                    if (!opened) {
                        Toast.makeText(context, context.getString(R.string.common_no_app_for_file), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
