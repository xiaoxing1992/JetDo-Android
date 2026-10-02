package com.arkdo.android.core.data.session

/**
 * 详情页 → 列表页的「已读状态」更新载荷（对齐鸿蒙 TopicReadStateEventBus 的 TopicReadState）。
 *
 * 语义：离开详情页时结算一次 —— 让首页列表把对应主题的蓝点消掉（unseen=false）、
 * 并把未读徽标更新成真实剩余未读数。
 */
data class TopicReadStateUpdate(
    val topicId: Long,
    val unseen: Boolean,
    val unreadPosts: Int
)

/**
 * 已读状态源。详情页写入，列表页订阅。
 *
 * 按「搬语义、不搬机制」的项目惯例，鸿蒙 TopicReadStateEventBus 的订阅/发布机制在这里
 * 收敛成一个进程内单例：app 模块的 TopicDetailViewModel 注入并 publish，feature/home 的
 * HomeViewModel 注入并订阅，中间不需要 app 层的 EventBus 做桥。
 *
 * 语义与鸿蒙一致的点：同步回调（publish 立刻逐个通知订阅方）；订阅时先吐当前值一次
 * （对齐鸿蒙首页「先取当前已知值，再订阅后续变化」的惯例）。重复投递同一条对订阅方
 * 是无害幂等操作（按 topicId 精确匹配行数据重建）。
 */
interface TopicReadStateSource {
    fun current(): TopicReadStateUpdate?

    fun publish(update: TopicReadStateUpdate)

    /** 订阅（先吐当前值）。返回值用来退订。 */
    fun subscribe(listener: (TopicReadStateUpdate) -> Unit): () -> Unit
}

/** 默认实现：进程内单例。由 app 的 DI 绑定（见 HomeModule 的 provideSessionVersionSource 同款做法）。 */
class DefaultTopicReadStateSource : TopicReadStateSource {
    private var latest: TopicReadStateUpdate? = null
    private val listeners = mutableListOf<(TopicReadStateUpdate) -> Unit>()

    override fun current(): TopicReadStateUpdate? = latest

    override fun publish(update: TopicReadStateUpdate) {
        latest = update
        // 拷贝一份再遍历：监听器回调里可能退订（修改本列表）。
        listeners.toList().forEach { listener ->
            runCatching { listener(update) }
        }
    }

    override fun subscribe(listener: (TopicReadStateUpdate) -> Unit): () -> Unit {
        latest?.let(listener)
        listeners += listener
        return { listeners.remove(listener) }
    }
}
