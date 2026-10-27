package com.jetdo.android.services.compliance

import android.content.Context

/**
 * 首启隐私同意状态（工信部合规：个人信息处理须在用户同意后进行）。
 *
 * 本应用**不做任何远程数据上报**：崩溃现场只写本机日志（见 DebugLogStore /
 * FileLoggerDaemon），用户可在「设置 → 诊断日志」自行查看与导出。因此这个闸门
 * 现在只管一件事——未同意前不渲染主界面（MainActivity 首启弹窗）。
 *
 * [POLICY_VERSION] 对应 docs/publish/privacy-policy.md 的实质内容版本：
 * 政策有实质变更时递增，老用户会在下次启动重新看到同意弹窗。
 */
object PrivacyConsentStore {

    private const val PREFS_NAME = "privacy_consent"
    private const val KEY_AGREED_VERSION = "agreed_version"

    /**
     * 当前政策版本。历史：
     * - 1 = 首版（崩溃上报走腾讯 Bugly，数据出境到第三方）
     * - 2 = 崩溃上报改走 ACRA + 自建服务器，不再有第三方接收方
     * - 3 = 移除远程崩溃上报，应用不再向开发者上传任何数据（实质变更，老用户重签）
     */
    const val POLICY_VERSION = 3

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
