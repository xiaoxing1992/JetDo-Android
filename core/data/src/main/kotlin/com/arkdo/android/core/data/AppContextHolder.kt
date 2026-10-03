package com.arkdo.android.core.data

import android.content.Context

/**
 * 应用级 Context 持有者。
 *
 * 服务层大量逻辑是 object 单例（DohManager / CookedHtmlParser / ArkWebNetworkBridge…），
 * 无法走构造注入；统一在 ArkDoApplication 启动时初始化，
 * 之后通过 [getString] 取本地化文案。
 *
 * [resolver] 供 JVM 单元测试注入（无 Android 环境）：装上后优先走它。
 */
object AppContextHolder {

    @Volatile
    private lateinit var appContext: Context

    /** 测试钩子：JVM 单测无 Android 环境，装上后 getString 优先走这里；生产代码勿用。 */
    @Volatile
    @JvmStatic
    var resolver: ((resId: Int, args: Array<out Any?>) -> String)? = null

    /** 由 Application.onCreate 调用；进程存活期内只初始化一次。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 取本地化字符串（支持格式化参数）。 */
    fun getString(resId: Int, vararg args: Any?): String =
        resolver?.invoke(resId, args) ?: appContext.getString(resId, *args)
}
