package com.arkdo.android.services.topic

import android.content.Context
import com.arkdo.android.core.data.AppContextHolder
import com.arkdo.android.R
import com.arkdo.android.data.models.TopicTag

// 抽奖帖提醒的判定与文案。抽成纯函数模块(不碰 UI、不碰存储)是为了能单测:
// 「什么算抽奖帖」这条判定牵着三个标签条件与两个状态条件,靠手点页面回归成本太高。
// 对齐鸿蒙 LotteryTopicRule.ets。
//
// 规则出处:官方公告帖「进一步优化对抽奖帖回复被举报的处理方式」(topic 1993635)——
// 抽奖帖里凑字数/复读/与奖品无关的回复被举报后,一律删除回复 + 记录举报次数 + 永久禁言。

// 公告帖 id。卡片底部「查看官方公告」按站内链接直开原生详情(LinkRouter 认这个形状的 URL)。
// 将来官方换公告,改这一个数字即可。
const val LOTTERY_RULES_TOPIC_ID: Long = 1993635L
const val LOTTERY_RULES_TOPIC_URL: String = "https://linux.do/t/topic/$LOTTERY_RULES_TOPIC_ID"

// 「高级推广」标签(slug 1514-tag)。高级推广帖里的抽奖不受该规则约束,命中即不提示——
// 排除判定优先于命中判定,两个标签同时挂着时高级推广赢。
private const val PROMOTION_TAG_ID: Long = 1514L
private val PROMOTION_TAG_NAME: String get() = AppContextHolder.getString(R.string.badge_promoter)

// 抽奖标签按「包含」匹配而非全等,顺带覆盖「抽奖活动」这类变体。
private val LOTTERY_TAG_KEYWORD: String get() = AppContextHolder.getString(R.string.lottery_title)

// 回复框占位文字:顶层回复(参与抽奖的那条)才替换,定向回复某楼仍显示「回复<昵称>」。
val LOTTERY_REPLY_PLACEHOLDER: String get() = AppContextHolder.getString(R.string.lottery_hint)

// 回复浮层顶上的一行提醒。只提示不拦截——拦截会误伤正常回复,而这行字抬眼就能看见。
val LOTTERY_REPLY_HINT: String get() = AppContextHolder.getString(R.string.lottery_rule2)

/** 抽奖提醒的展示前提(开关由调用方另判,这里只管帖子本身)。 */
data class LotteryNoticeContext(
    val tags: List<TopicTag>,
    val closed: Boolean,
    val archived: Boolean,
    // 楼主 user id;未知传 0。
    val createdById: Long,
    // 当前登录用户 id;未登录传 0。
    val currentUserId: Long
)

/** 标签里是否有「抽奖」(按包含匹配)。 */
fun hasLotteryTag(tags: List<TopicTag>): Boolean =
    tags.any { it.name.trim().contains(LOTTERY_TAG_KEYWORD) }

/**
 * 标签里是否有「高级推广」。
 *
 * id 优先、name 兜底:帖子详情接口下发的标签是对象(带 id),但列表/书签等接口只给字符串,
 * 经 TopicService.mapTag 后就只剩 name。两条都判,判定在任何数据形态下都成立。
 */
fun hasAdvancedPromotionTag(tags: List<TopicTag>): Boolean =
    tags.any { it.id == PROMOTION_TAG_ID || it.name.trim() == PROMOTION_TAG_NAME }

/** 是否该在本帖顶部显示抽奖提醒卡。 */
fun shouldShowLotteryNotice(context: LotteryNoticeContext): Boolean {
    // 排除优先:高级推广帖不适用该规则。
    if (hasAdvancedPromotionTag(context.tags)) {
        return false
    }
    if (!hasLotteryTag(context.tags)) {
        return false
    }
    // 已开奖/已归档 ⇒ 回不了帖,也就没有被举报的风险。
    if (context.closed || context.archived) {
        return false
    }
    // 自己办的抽奖不用被教育。两个 id 都得是有效值才判,免得未登录(0)撞上未知楼主(0)。
    if (context.createdById > 0 && context.currentUserId > 0 && context.createdById == context.currentUserId) {
        return false
    }
    return true
}
