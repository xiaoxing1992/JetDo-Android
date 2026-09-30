package com.arkdo.android.navigation

import timber.log.Timber
import java.net.URI
import java.net.URLDecoder

/**
 * 推送通知深链解析出来的目标。
 *
 * 目前只有通知中心一条链路（Manifest 里注册的是 `arkdo://notification`），
 * 但通知点进来往往要直达某个话题的某一楼，所以话题是独立的目标类型而不是
 * 「通知中心 + 参数」。
 */
sealed interface DeepLinkTarget {

    /** 只进通知中心，没有具体话题。 */
    data object NotificationInbox : DeepLinkTarget

    /** 直达话题详情；[postNumber] 非空时定位到该楼层。 */
    data class Topic(val topicId: Long, val postNumber: Int?) : DeepLinkTarget
}

/**
 * 深链事件通道：把「收到一个 intent」这件事从 Activity 送到 NavHost。
 *
 * 为什么需要它 —— MainActivity 拿得到 intent，但导航权在 ArkDoNavHost 手里的
 * navController 上，两者之间隔着一整个 Compose 树，没有直接的引用路径。
 * 项目里已有的 ChallengeEventBus / SessionEventBus / AppStateEventBus 都是
 * 同一类结构，这里沿用（而不是往 MainActivity 里塞一个 navController 引用：
 * 那样会让 Activity 与具体路由耦合，冷启动时 NavHost 甚至还没建出来）。
 *
 * **积压语义**：冷启动（从通知点开、App 没在跑）时 intent 在 onCreate 就拿到了，
 * 而 NavHost 要等首帧才订阅。这时候直接广播会丢。所以没有订阅者时把目标暂存下来，
 * 等第一个订阅者上钩时补发一次，然后清空 —— 保证一次 intent 只驱动一次导航。
 */
object DeepLinkEventBus {

    /** Manifest 中 intent-filter 注册的 scheme。 */
    const val SCHEME = "arkdo"

    /** 通知深链的 host。 */
    const val HOST_NOTIFICATION = "notification"

    private val listeners = mutableMapOf<Long, (DeepLinkTarget) -> Unit>()
    private var nextListenerId = 0L
    private var pending: DeepLinkTarget? = null

    fun subscribe(listener: (DeepLinkTarget) -> Unit): () -> Unit {
        val id = nextListenerId++
        listeners[id] = listener
        // 订阅晚了的补发。放在注册之后：补发过程中报错也不会漏掉这个订阅者。
        pending?.let { queued ->
            pending = null
            runCatching { listener(queued) }.onFailure {
                Timber.e(it, "[ArkDO][DeepLink] 补发积压目标失败")
            }
        }
        return { listeners.remove(id) }
    }

    fun dispatch(target: DeepLinkTarget) {
        Timber.i("[ArkDO][DeepLink] dispatch target=%s", target.toString())
        if (listeners.isEmpty()) {
            pending = target
            Timber.i("[ArkDO][DeepLink] 暂无订阅者，积压一份等 NavHost 就绪")
            return
        }
        notifyListeners(target)
    }

    /**
     * 解析深链 URL。不认识的 scheme / host、或者话题 id 非法，一律返回 null。
     *
     * 用 [java.net.URI] 而不是 `android.net.Uri`：后者在 JVM 单测里是一堆 Stub，
     * 解析逻辑会变成测不了的代码 —— 而这段逻辑恰恰是最容易写错、最该被测的部分。
     */
    fun parse(url: String?): DeepLinkTarget? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        // 自定义 scheme 的 `arkdo://notification` 里，authority 就是 host。
        if (!HOST_NOTIFICATION.equals(uri.host, ignoreCase = true)) return null

        val params = parseQuery(uri.rawQuery)
        // 话题 id 必须是正整数才值得跳；解析不出来就退回通知中心，
        // 而不是把用户丢在一个必然报错的话题页上。
        val topicId = params["topicId"]?.toLongOrNull()?.takeIf { it > 0 } ?: return DeepLinkTarget.NotificationInbox
        val postNumber = params["postNumber"]?.toIntOrNull()?.takeIf { it > 0 }
        return DeepLinkTarget.Topic(topicId, postNumber)
    }

    private fun notifyListeners(target: DeepLinkTarget) {
        listeners.values.toList().forEach { listener ->
            runCatching { listener(target) }.onFailure {
                Timber.e(it, "[ArkDO][DeepLink] 订阅者抛出异常")
            }
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            val key = if (separator < 0) pair else pair.substring(0, separator)
            val value = if (separator < 0) "" else pair.substring(separator + 1)
            result[decode(key)] = decode(value)
        }
        return result
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)

    /** 单测用：清掉单例里跨用例残留的订阅者与积压。 */
    internal fun resetForTest() {
        listeners.clear()
        pending = null
        nextListenerId = 0L
    }
}
