package com.arkdo.android.services.compliance

import android.content.Context

/**
 * 首启隐私同意状态（工信部合规：个人信息处理须在用户同意后进行）。
 *
 * 唯一被此闸门约束的是腾讯 Bugly 崩溃上报（远程采集）：
 * 未同意前 `ArkDoApplication.initCrashReportIfAllowed()` 不装 SDK；
 * MainActivity 首启弹窗拿到同意后才初始化。
 *
 * [POLICY_VERSION] 对应 docs/publish/privacy-policy.md 的实质内容版本：
 * 政策有实质变更时递增，老用户会在下次启动重新看到同意弹窗。
 */
object PrivacyConsentStore {

    private const val PREFS_NAME = "privacy_consent"
    private const val KEY_AGREED_VERSION = "agreed_version"

    /** 当前政策版本。历史：1 = 首版（含 Bugly 崩溃上报声明）。 */
    const val POLICY_VERSION = 1

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 已同意的政策版本；从未同意返回 0。 */
    fun agreedVersion(context: Context): Int =
        prefs(context).getInt(KEY_AGREED_VERSION, 0)

    /** 是否还需要弹同意框（未同意过，或同意的是旧版政策）。 */
    fun needsConsent(context: Context): Boolean =
        agreedVersion(context) < POLICY_VERSION

    /** 记录用户同意了当前版本。 */
    fun agree(context: Context) {
        prefs(context).edit().putInt(KEY_AGREED_VERSION, POLICY_VERSION).apply()
    }
}
