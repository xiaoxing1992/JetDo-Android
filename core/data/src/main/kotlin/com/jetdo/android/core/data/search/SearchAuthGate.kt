package com.jetdo.android.core.data.search

/**
 * 搜索登录守卫。
 *
 * linux.do 的 `/search.json` 等搜索接口**要求登录**（实测未登录返回
 * `403 {"errors":["You need to be logged in to do that."],"error_type":"not_logged_in"}`）。
 * feature 层不依赖 app 层，登录态由 app 层实现这个接口注入：
 * 已登录返回 true 放行；未登录返回 false（内部已触发登录弹层），搜索层就地拦截。
 */
/** 单方法接口:支持 `SearchAuthGate { ... }` 的 SAM 转换,DI/测试注入都简洁。 */
fun interface SearchAuthGate {
    /** 已登录放行；未登录请求登录并返回 false。 */
    fun ensureLoggedIn(): Boolean
}