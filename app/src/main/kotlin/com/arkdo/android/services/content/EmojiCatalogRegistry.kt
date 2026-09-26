package com.arkdo.android.services.content

import com.arkdo.android.common.constants.AppConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

// ==========================================================================
// 表情字典：name → url。结构就是 Discourse `GET /emojis.json` 的原样响应
// （{ 组名: [{ name, url, tonable, ... }] }），覆盖标准 twemoji + 该站的自定义表情组。
//
// 用途有两个，与鸿蒙 EmojiUrlRegistry.ets 完全对应：
//  1. 把 user_status.emoji（如 "lollipop"、"tieba_003"）这类**只有短名**的具名表情渲染成图片；
//  2. 给表情选择器提供分组数据。
//
// 数据从**当前论坛**动态拉取（见 EmojiCatalogService），不内置 assets —— 内置的那份只对
// linux.do 成立，别的论坛自定义表情完全不同，拿它渲染等于给用户看一堆不存在的图。
//
// 状态整体打包成一个不可变快照后**原子替换**：读侧（UI 线程）完全无锁，写侧（IO 协程）
// 一次替换即生效，不会读到"表清了但 groups 还在"这种中间态。
// ==========================================================================

/** 一个表情条目。`createdBy` 仅自定义表情有（"by @xxx"）。 */
data class EmojiItem(
    val name: String,
    val url: String,
    val groupKey: String,
    val tonable: Boolean,
    val createdBy: String? = null
)

/** 一个表情分组（Discourse 的 key 就是组名，如 `smileys_&_emotion`）。 */
data class EmojiGroup(
    val key: String,
    val items: List<EmojiItem>
)

private data class EmojiCatalogState(
    val urlByName: Map<String, String>,
    val itemByName: Map<String, EmojiItem>,
    val groups: List<EmojiGroup>,
    /** 是否尝试过装表（成功或失败都算）。失败时表为空但已就绪，消费方按"查不到"降级。 */
    val loaded: Boolean
)

private val EMPTY_STATE = EmojiCatalogState(
    urlByName = emptyMap(),
    itemByName = emptyMap(),
    groups = emptyList(),
    loaded = false
)

@Volatile
private var state: EmojiCatalogState = EMPTY_STATE

/**
 * 目录版本号，每次装载/清空 +1。
 *
 * 表情表是异步拉来的，UI（表情选择器）可能在"表还是空的时候"就组合了。鸿蒙的做法是
 * 只在 `aboutToAppear` 读一次快照，那样面板打开晚于数据到达才看得到内容；这里给一个
 * 可观察信号，UI 订阅后表一到就自动刷新，不必等下次重开。
 */
private val _revision = MutableStateFlow(0)
val emojiCatalogRevision: StateFlow<Int> = _revision.asStateFlow()

private val catalogJson = Json { ignoreUnknownKeys = true }

/**
 * 装载一份 `/emojis.json` 响应。返回收录条数（0 = 解析失败或空表）。
 *
 * 逐组遍历而非直接反序列化成 Map：真实响应里偶尔有非数组的值（坏站点 / 未来加字段），
 * 整体反序列化会因一处不合规把整张表丢掉，逐组跳过只损失那一组。与鸿蒙实现同行为。
 */
fun loadEmojiUrlsFromJson(json: String): Int {
    val root = runCatching { catalogJson.parseToJsonElement(json) }.getOrNull()
    if (root !is JsonObject) {
        publish(EMPTY_STATE.copy(loaded = true))
        return 0
    }

    val urlByName = LinkedHashMap<String, String>()
    val itemByName = LinkedHashMap<String, EmojiItem>()
    val groups = mutableListOf<EmojiGroup>()

    for ((groupKey, groupValue) in root) {
        if (groupValue !is JsonArray) continue
        val items = mutableListOf<EmojiItem>()
        for (element in groupValue) {
            val obj = element as? JsonObject ?: continue
            val name = obj.stringOrNull("name") ?: continue
            val rawUrl = obj.stringOrNull("url") ?: continue
            if (name.isEmpty() || rawUrl.isEmpty()) continue
            val item = EmojiItem(
                name = name,
                url = resolveEmojiUrl(rawUrl),
                groupKey = groupKey,
                tonable = obj["tonable"]?.jsonPrimitive?.booleanOrNull == true,
                createdBy = obj.stringOrNull("created_by")
            )
            items.add(item)
            urlByName[item.name] = item.url
            itemByName[item.name] = item
        }
        if (items.isNotEmpty()) {
            groups.add(EmojiGroup(key = groupKey, items = items))
        }
    }

    publish(
        EmojiCatalogState(
            urlByName = urlByName,
            itemByName = itemByName,
            groups = groups,
            loaded = true
        )
    )
    return urlByName.size
}

/** 具名表情短名 → 可直接喂图片库的绝对 url；未收录（别名 / 未知）→ null，调用方跳过渲染。 */
fun getEmojiUrl(name: String?): String? {
    if (name.isNullOrEmpty()) return null
    return state.urlByName[name]
}

fun getEmojiItem(name: String?): EmojiItem? {
    if (name.isNullOrEmpty()) return null
    return state.itemByName[name]
}

/** 目录快照。调用方可以安全地切分组 / 筛选，不会改坏全局注册表。 */
fun getEmojiGroups(): List<EmojiGroup> = state.groups

fun hasLoadedEmojiUrls(): Boolean = state.loaded

/** 已收录条数。0 = 还没拉到（首启）或拉到的是空表，消费方按"查不到"处理即可。 */
fun emojiCount(): Int = state.urlByName.size

/** 短码是否可在目录里查到。用于"退格删整段 :name:"这类编辑判定。 */
fun isKnownEmoji(name: String): Boolean = state.urlByName.containsKey(name)

/** 清空字典。切站时调用——各站的自定义表情完全不同，旧站的表留着会渲染出不存在的图。 */
fun resetEmojiUrls() {
    publish(EMPTY_STATE)
}

/** 原子替换快照并推进版本号，通知在观察的 UI。 */
private fun publish(next: EmojiCatalogState) {
    state = next
    _revision.value += 1
}

/**
 * 相对 URL 补全为绝对 URL。
 *
 * - 自定义表情已是协议相对（`//linuxdo-uploads.s3.ldstatic.com/...`）→ 补 https；
 * - 标准 twemoji 是站内相对（`/images/emoji/...`）→ 挂 CDN（主站域名在 TLS 层被按 SNI 阻断，
 *   原生请求取不到图）；
 * - 已是绝对地址 → 原样返回。
 *
 * 注意：`STATIC_CDN_BASE` 目前是常量（只服务 linux.do）。多站点（P0-6）落地后这里要跟着
 * 当前站点走，届时改读站点配置即可——放在函数里而不是模块级常量，正是为了那时不用改调用方。
 */
private fun resolveEmojiUrl(rawUrl: String): String {
    if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) return rawUrl
    if (rawUrl.startsWith("//")) return "https:$rawUrl"
    if (rawUrl.startsWith("/")) return "${AppConstants.STATIC_CDN_BASE}$rawUrl"
    return "${AppConstants.STATIC_CDN_BASE}/$rawUrl"
}

private fun JsonObject.stringOrNull(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    // isString 之外也接受数字型（真实响应里偶有 name 写成数字），统一取 content。
    return primitive.content.takeIf { it.isNotEmpty() }
}
