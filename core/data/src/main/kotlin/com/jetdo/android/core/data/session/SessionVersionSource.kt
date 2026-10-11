package com.jetdo.android.core.data.session

/**
 * 会话版本源。登录、登出、换号、DoH 变更都会把版本加一。
 *
 * feature 层不依赖 app 层的 SessionEventBus，由 app 注入实现。
 * 版本单调递增，订阅方用已处理版本去重。
 */
interface SessionVersionSource {
    fun currentVersion(): Long

    /** 之后每次版本增加都会回调。返回值用来退订。 */
    fun subscribe(listener: (Long) -> Unit): () -> Unit
}
