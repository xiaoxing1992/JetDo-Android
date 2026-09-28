package com.arkdo.android.services.content

// 全局「同一时刻只有一个媒体在播」协调器。音频/视频播放器各自注册一个停止回调并拿到唯一 id;
// 某个播放器开始播放时调用 notifyStarted(id),其余所有播放器收到通知即停止并复位 UI。
// 这样在同一帖子里先播 A 再播 B,A 会自动停下。对齐鸿蒙 MediaPlaybackCoordinator.ets。
object MediaPlaybackCoordinator {

    private val listeners = LinkedHashMap<Int, () -> Unit>()
    private var nextId: Int = 1
    // 当前活跃(最后一个发起播放的)播放器 id。用于「下载竞态」:先点 A(下载中)再点 B 后,
    // A 下载完时若已不是活跃者(activeId 已是 B),就不该自动开播,避免 A、B 同时响。
    private var activeId: Int = 0

    fun register(onStop: () -> Unit): Int = synchronized(this) {
        val id = nextId
        nextId++
        listeners[id] = onStop
        id
    }

    fun unregister(id: Int) = synchronized(this) {
        listeners.remove(id)
        if (activeId == id) {
            activeId = 0
        }
        Unit
    }

    // 某播放器(activeId)发起播放:记为活跃者并通知其余所有播放器停止。
    // onStop 在锁外调用:回调里播放器只 pause 自己,不回调协调器,但锁外更稳。
    fun notifyStarted(activeId: Int) {
        val stops: List<() -> Unit> = synchronized(this) {
            this.activeId = activeId
            listeners.filterKeys { it != activeId }.values.toList()
        }
        stops.forEach { it() }
    }

    // 该 id 是否仍是当前活跃者(下载完成后据此决定是否自动开播)。
    fun isActive(id: Int): Boolean = synchronized(this) { activeId == id }
}
