package com.arkdo.android.services.content

import android.content.Context
import timber.log.Timber

/**
 * 上传短码 → 真实直链（`/uploads.json` 响应里的 `url` 字段）。
 *
 * **为什么非要有这张表**：linux.do 是 S3 + CDN 部署，`/uploads/short-url/<hash>.<ext>` 这条
 * 路由**对图片不回字节** —— 实测（同一个 hash 段、带上完整浏览器头与会话 cookie）稳定返回
 * Discourse 自己的 404 页；而 `url` 字段给的 CDN 直链
 * （`https://cdn3.ldstatic.com/original/...`）无需登录即可 200。
 *
 * 于是「`upload://X.ext` → `/uploads/short-url/X.ext`」这个纯字符串替换对**图片是死路**：
 * 服务端确实承认这条 short_path（上传响应里就是它），但真正能取到图片的直链不在短码里，
 * 也无法从短码反推 —— CDN host 与 `original/4X/<sha1[0]>/<sha1[1]>/<sha1[2]>/<sha1><ext>`
 * 这串分片目录都是部署相关的（sha1 还得先对 base62 做一次解码），写死等于把实现绑死在
 * 这一个站点上。
 *
 * 所以：**在上传成功那一刻把映射记下来**，本地预览解析 URL 时查表。只影响客户端预览，
 * 正文 markdown 里依旧是官方短码 `upload://`（与 Discourse 官方客户端、鸿蒙版一致），
 * 交给服务端自己 cook。
 *
 * 落盘（SharedPreferences）而非仅内存：草稿是存在服务端的，App 重启后恢复出来的正文里
 * 还是短码 —— 只留内存的话，「上传完 → 杀进程 → 重进编辑页 → 预览」图片又会变空白，
 * 正是这次要修的症状。
 */
object UploadShortcodeRegistry {

    private const val PREFS_NAME = "arkdo_upload_urls"
    private const val KEY_ENTRIES = "entries"
    private const val SEP_ENTRY = '\n'
    private const val SEP_PAIR = '\t'

    /** 上限只是防长年累积；单次上传最多记 2 条，正常用户远达不到。 */
    private const val MAX_ENTRIES = 200

    private val map = LinkedHashMap<String, String>()
    private var prefs: android.content.SharedPreferences? = null
    private var loaded = false

    /** App 启动时调一次；失败就退化成纯内存表，不影响功能。 */
    fun init(context: Context) {
        if (loaded) return
        synchronized(map) {
            if (loaded) return
            runCatching {
                val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs = p
                p.getString(KEY_ENTRIES, null)?.let { blob ->
                    blob.split(SEP_ENTRY).forEach { line ->
                        val i = line.indexOf(SEP_PAIR)
                        if (i > 0) {
                            // 载入时也过一遍 normalize：早于该修复写入的协议相对值要补成 https。
                            val url = normalize(line.substring(i + 1))
                            if (url.isNotEmpty()) map[line.substring(0, i)] = url
                        }
                    }
                }
                loaded = true
                Timber.i("[JetDo][Upload] 短码映射载入 size=%d", map.size)
            }.onFailure {
                loaded = true
                Timber.i("[JetDo][Upload] 短码映射载入失败 msg=%s", it.message)
            }
        }
    }

    /**
     * 记一条上传结果。[shortUrl] / [shortPath] 两种写法都记 —— markdown 里图片用短码、
     * 音视频用 short_path，查表时按出现形式直接命中。任一为空则跳过。
     *
     * 存的必须是**绝对** URL：linux.do 对音视频（`.xz` 伪装件）回的 `url` 是协议相对的
     * `//linuxdo-uploads.s3.ldstatic.com/original/...`，直接交给播放器 / DownloadManager
     * 会当成没有 scheme 的 Uri 解析失败，故这里统一补成 https。
     */
    fun remember(shortUrl: String, shortPath: String, url: String) {
        val absolute = normalize(url)
        if (absolute.isEmpty()) return
        val targets = listOf(shortUrl, shortPath).filter { it.isNotEmpty() }
        if (targets.isEmpty()) return
        synchronized(map) {
            targets.forEach { map.remove(it); map[it] = absolute }
            while (map.size > MAX_ENTRIES) {
                val eldest = map.keys.firstOrNull() ?: break
                map.remove(eldest)
            }
            persistLocked()
        }
    }

    /** `//host/path` → `https://host/path`；已是绝对 http(s) 的原样返回。 */
    private fun normalize(url: String): String {
        val u = url.trim()
        return when {
            u.isEmpty() -> ""
            u.startsWith("//") -> "https:$u"
            else -> u
        }
    }

    fun lookup(raw: String): String? = synchronized(map) { map[raw] }

    private fun persistLocked() {
        val p = prefs ?: return
        runCatching {
            val blob = map.entries.joinToString(SEP_ENTRY.toString()) { "${it.key}$SEP_PAIR${it.value}" }
            p.edit().putString(KEY_ENTRIES, blob).apply()
        }.onFailure {
            Timber.i("[JetDo][Upload] 短码映射落盘失败 msg=%s", it.message)
        }
    }
}
