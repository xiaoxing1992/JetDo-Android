package com.arkdo.android.services.content

import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/content/DownloadEventBus.ets 逐字段对齐。
// host webview 下载委托的结果经此总线上抛给根页面做 toast + 打开。
// ==========================================================================

data class DownloadResult(
    val success: Boolean,
    val fileName: String,
    val filePath: String,
    val message: String
)

typealias DownloadResultListener = (DownloadResult) -> Unit

object DownloadEventBus {
    private val listeners = mutableMapOf<Long, DownloadResultListener>()
    private var nextListenerId = 0L

    fun subscribe(listener: DownloadResultListener): () -> Unit {
        val id = nextListenerId++
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    fun notify(result: DownloadResult) {
        Timber.i("[ArkDO][Download] success=%s name=%s path=%s", result.success, result.fileName, result.filePath)
        listeners.values.toList().forEach { l ->
            runCatching { l(result) }.onFailure {
                Timber.e(it, "[ArkDO][Download] listener threw")
            }
        }
    }
}
