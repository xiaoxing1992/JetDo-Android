package com.arkdo.android.services.announce

import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.network.NetworkResults
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber

// 版本公告帖:每个版本在此帖发一条回复,开头写 [v<versionCode>],应用启动时用当前
// versionCode 匹配对应那条弹出。换帖只改这里(对齐鸿蒙 VersionAnnouncement.ets;
// Android 版发布前把 TOPIC/USERNAME 换成 Android 版自己的公告帖与发帖账号)。
const val ANNOUNCE_TOPIC_ID: Long = 2590051L
// 只取发帖本人的回复(帖子里别人插楼不干扰),对应 URL 的 username_filters。
const val ANNOUNCE_USERNAME: String = "joeyyin"

// 「发现新版本/本次更新」总开关。当前公告源是鸿蒙版的发布日志帖(2590051/joeyyin,
// [v900008+] 体系),与 Android versionCode 体系对不上——开着会每次冷启动误弹鸿蒙的
// 更新公告(实测)。Android 版自己的公告帖定下来后置 true,扫描/弹层/跳楼链路已就绪。
const val ANNOUNCE_PROMPT_ENABLED: Boolean = false
// 从帖尾往前最多「补拉」多少条未加载的楼找匹配。首屏已返回的楼(前 20,多为较旧的版本公告)
// 会先被免费扫一遍;这个上限只约束帖尾那些要单独补拉的新楼,避免帖子很大时发太多请求。
private const val MAX_TAIL_FETCH: Int = 15

// 一条已有主版本公告被扫到的诊断前缀:hilog/DevEco 搜 ANNOUNCE_DIAG,Android 侧 logcat 同名。
private const val LOG_TAG = "[JetDo][ANNOUNCE_DIAG]"

// 版本标记正则:[v900008]。Discourse 里这写法无匹配引用,会原样留在 cooked 中,
// 哪怕被 **加粗** 包成 <strong>[v900008]</strong>,子串仍在,照样命中。
private val MARKER_PATTERN = Regex("\\[v(\\d+)\\]")

data class VersionAnnouncement(
    // 跳楼要用:公告帖 id + 楼号。
    val topicId: Long,
    val postNumber: Int,
    // 该楼 [vXXXX] 标记里声明的版本号。
    val versionCode: Int,
    // 完整正文 HTML(含 [vXXXX] 标记,不剥离),直接喂 PostRichContent。
    val cookedHtml: String
)

data class AnnouncementScanResult(
    // 命中当前 versionCode 的那条公告(没有则 null)。
    val current: VersionAnnouncement?,
    // 版本号高于当前 versionCode 的公告里最高的那条——即"有新版可升级"(没有则 null)。
    val newer: VersionAnnouncement?
)

// —— Discourse 原始响应里我们用到的字段(其余忽略)——
@Serializable
data class AnnounceRawPost(
    val id: Long = 0,
    val cooked: String? = null,
    @SerialName("post_number") val postNumber: Int? = null
)

@Serializable
data class AnnounceRawPostStream(
    val posts: List<AnnounceRawPost> = emptyList(),
    // 该帖(经 username_filters 过滤后)全部楼层 id 的完整有序列表,不分页——
    // 这是"从后往前匹配"的依据:posts 默认只含前 20 楼,最新的公告要靠 stream 末尾的 id 单独补拉。
    val stream: List<Long> = emptyList()
)

@Serializable
data class AnnounceRawTopic(
    @SerialName("post_stream") val postStream: AnnounceRawPostStream? = null
)

class VersionAnnouncementService(private val bridge: JsonNetworkClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // 一趟扫描同时产出「当前版本公告」和「更高版本公告」:
    // 从 stream 末尾(最新)往前走,途中遇到比当前版本高的记为 newer(取最高),
    // 一撞上当前版本那楼就停——它上面不可能再有更高版本了,补拉次数因此约等于"比你新的版本数"。
    // 任何失败/找不到都返回空结果(即不弹窗),不抛错。
    suspend fun scan(versionCode: Int): AnnouncementScanResult {
        try {
            val path = "/t/$ANNOUNCE_TOPIC_ID.json?username_filters=$ANNOUNCE_USERNAME"
            Timber.i("%s 开始扫公告 versionCode=%d 目标标记=[v%d] path=%s", LOG_TAG, versionCode, versionCode, path)
            val res = bridge.getJson(path)
            if (!res.success || res.data == null) {
                Timber.i("%s 拉公告帖失败 status=%d", LOG_TAG, res.statusCode)
                return AnnouncementScanResult(null, null)
            }
            val topic = json.decodeFromString<AnnounceRawTopic>(res.data)
            val stream = topic.postStream?.stream.orEmpty()
            val loaded = topic.postStream?.posts.orEmpty()
            Timber.i(
                "%s stream 楼层数=%d 首屏已加载楼=%d", LOG_TAG, stream.size, loaded.size
            )
            if (stream.isEmpty()) {
                Timber.i("%s stream 为空——username_filters 可能没匹配到回复", LOG_TAG)
                return AnnouncementScanResult(null, null)
            }

            // 首屏已返回的楼建索引:扫到它们时是免费的,不占补拉配额。
            val loadedById = loaded.associateBy { it.id }

            var current: VersionAnnouncement? = null
            var newer: VersionAnnouncement? = null
            var fetched = 0
            for (index in stream.indices.reversed()) {
                val id = stream[index]
                var post = loadedById[id]
                if (post == null) {
                    // 补拉配额用完后不再发请求,但继续往前走——剩下的首屏楼还能免费扫,
                    // 当前版本的公告常年就躺在那 20 楼里。
                    if (fetched >= MAX_TAIL_FETCH) continue
                    fetched++
                    post = fetchPostById(id)
                }
                val marked = post?.let { readAnnouncement(it) } ?: continue
                if (marked.versionCode == versionCode) {
                    current = marked
                    Timber.i("%s 命中当前版本:楼号=%d,停止向前扫", LOG_TAG, marked.postNumber)
                    break
                }
                if (marked.versionCode > versionCode && (newer == null || marked.versionCode > newer.versionCode)) {
                    newer = marked
                    Timber.i("%s 发现更高版本 v%d 楼号=%d", LOG_TAG, marked.versionCode, marked.postNumber)
                }
            }
            if (current == null) {
                Timber.i("%s 未找到 [v%d] 对应的公告(补拉 %d 楼)", LOG_TAG, versionCode, fetched)
            }
            return AnnouncementScanResult(current, newer)
        } catch (e: Exception) {
            Timber.i("%s 扫公告异常: %s", LOG_TAG, e.message)
            return AnnouncementScanResult(null, null)
        }
    }

    // 按单个 id 补拉一楼(stream 末尾那条通常不在首页返回的前 20 楼里)。
    private suspend fun fetchPostById(id: Long): AnnounceRawPost? {
        val path = "/t/$ANNOUNCE_TOPIC_ID/posts.json?post_ids%5B%5D=$id&include_suggested=false"
        val res = bridge.getJson(path)
        if (!res.success || res.data == null) {
            return null
        }
        // 单条畸形响应只跳过这一楼,不能让异常冒到外层 catch 去中止整轮补拉扫描。
        return try {
            json.decodeFromString<AnnounceRawTopic>(res.data).postStream?.posts?.firstOrNull()
        } catch (e: SerializationException) {
            Timber.i("%s 补拉楼 id=%d 响应不是有效 JSON,跳过", LOG_TAG, id)
            null
        }
    }

    // 单楼解析:带 [vXXXX] 标记则返回可展示的公告,否则返回 null。
    private fun readAnnouncement(post: AnnounceRawPost): VersionAnnouncement? {
        // 主帖(楼层1)是总更新日志,正文里罗列各版本号纯文本,不是某一版的公告——
        // 不跳过的话会被误判成"有新版本"。
        if (post.postNumber == 1) {
            return null
        }
        val cooked = post.cooked.orEmpty()
        val marked = readMarkerVersion(cooked)
        if (marked <= 0 || cooked.isBlank()) {
            return null
        }
        return VersionAnnouncement(
            topicId = ANNOUNCE_TOPIC_ID,
            postNumber = post.postNumber ?: 0,
            versionCode = marked,
            cookedHtml = cooked
        )
    }

    // 取该楼声明的版本号;一楼写了多个标记(如回顾旧版)时取最大的那个。无标记返回 0。
    private fun readMarkerVersion(cooked: String): Int =
        MARKER_PATTERN.findAll(cooked).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
}
