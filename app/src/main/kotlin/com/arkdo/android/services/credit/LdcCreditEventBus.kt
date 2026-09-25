package com.arkdo.android.services.credit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LDC 余额变化广播。详情页拿到新数据后叫醒个人中心——个人中心压在详情页下面，
 * 不会重新进页，没有这条广播入口行会一直停在进详情页之前的旧值。
 * 数据本身走 [LdcCreditSession] 的缓存，广播只负责叫醒。
 */
object LdcCreditEventBus {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun notifyCreditChanged() {
        _version.value += 1
    }
}
