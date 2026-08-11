package com.arkdo.android.services.site

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 站点 favicon 的本地缓存
 *
 * favicon 只能由 webview 引擎交出来，别无他法：
 *  · 原生 HTTP 客户端不走 DoH，对被污染的站点必然加载失败
 *    ——而那正是本功能的目标站点；
 *  · host webview 的 bridge fetch 是跨域请求，favicon.ico 不带 CORS 头，读不到字节。
 * 故走 WebView 的 onReceivedIcon 拿 Bitmap（引擎自己的网络栈，吃 DoH 且无 CORS），在此编码 PNG 落盘。
 *
 * 文件按 siteId 约定命名，不进 SharedPreferences：避免"文件已删而字段还在"的不同步，
 * 读取时查一次文件系统即可。
 */
class SiteIconStore private constructor() {

    companion object {
        private const val TAG = "SiteIconStore"
        private const val ICON_DIR_NAME = "site_icons"

        private fun dirOf(context: Context): File {
            return File(context.filesDir, ICON_DIR_NAME)
        }

        /**
         * 站点图标的约定路径（不保证存在）
         */
        fun pathOf(context: Context, siteId: String): File {
            return File(dirOf(context), "$siteId.png")
        }

        /**
         * 图片加载可直接使用的 uri；无图标时返回空串（调用方据此回退默认图标）
         */
        fun uriOf(context: Context, siteId: String): String {
            return try {
                val path = pathOf(context, siteId)
                if (path.exists()) "file://${path.absolutePath}" else ""
            } catch (e: Exception) {
                ""
            }
        }

        /**
         * 存 favicon。同站点重复存即覆盖——站点换了 logo 也能跟上。
         */
        fun save(context: Context, siteId: String, favicon: Bitmap) {
            var fos: FileOutputStream? = null
            try {
                val dir = dirOf(context)
                if (!dir.exists()) {
                    dir.mkdirs()
                }

                val path = pathOf(context, siteId)
                fos = FileOutputStream(path)
                favicon.compress(Bitmap.CompressFormat.PNG, 100, fos)
                fos.flush()

                Log.i(TAG, "saved icon site=$siteId")
            } catch (e: Exception) {
                Log.e(TAG, "save icon failed site=$siteId", e)
            } finally {
                try {
                    fos?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "close icon file failed", e)
                }
            }
        }

        /**
         * 删站点时清掉图标文件，不留孤儿
         */
        fun remove(context: Context, siteId: String) {
            try {
                val path = pathOf(context, siteId)
                if (path.exists()) {
                    path.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "remove icon failed site=$siteId", e)
            }
        }
    }
}
