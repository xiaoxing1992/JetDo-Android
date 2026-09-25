package com.arkdo.android.services.credit

import com.arkdo.android.common.constants.AppConstants
import com.arkdo.android.data.models.LdcUserInfo
import com.arkdo.android.services.network.ArkWebJsonExecutor
import com.arkdo.android.services.network.HttpHeaderEntry
import com.arkdo.android.services.network.WebFetchScriptFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val CreditHeaders = listOf(HttpHeaderEntry("X-Requested-With", "XMLHttpRequest"))

data class CreditFetch(val status: Int, val body: String?, val message: String)

/** 在 credit.linux.do 同源 WebView 里取余额。401 与解析失败由调用方决定怎么画入口行。 */
suspend fun fetchLdcUserInfo(executor: ArkWebJsonExecutor): CreditFetch =
    fetchCredit(executor, "/api/v1/oauth/user-info")

internal suspend fun fetchCredit(executor: ArkWebJsonExecutor, path: String): CreditFetch {
    val script = WebFetchScriptFactory.createHeaderedFetchScript(path, CreditHeaders, AppConstants.CREDIT_HOME_URL)
        .replace("const baseUrl = 'https://linux.do';", "const baseUrl = '${AppConstants.CREDIT_BASE_URL}';")
    val raw = runCatching { executor.run(script) }.getOrNull()
        ?: return CreditFetch(0, null, "LDC 请求失败。")
    val envelope = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return CreditFetch(0, null, "LDC 请求失败。")
    val status = envelope["statusCode"]?.jsonPrimitive?.intOrNull ?: 0
    val body = envelope["body"]?.jsonPrimitive?.contentOrNull
    val error = envelope["errorMessage"]?.jsonPrimitive?.contentOrNull.orEmpty()
    return if (status in 200..299 && body != null) CreditFetch(status, body, "") else CreditFetch(status, null, error)
}

/** 把一次 user-info 结果收成入口行事件。解析不出账户时按失败处理，不把空余额写成 0。 */
fun ldcUserInfoEvent(fetch: CreditFetch): LdcEntryEvent {
    if (fetch.status == 401) return LdcEntryEvent.Unauthorized
    val parsed: LdcUserInfo? = fetch.body?.let(LdcCreditParser::parseUserInfo)
    return if (parsed != null) LdcEntryEvent.Loaded(parsed) else LdcEntryEvent.LoadFailed
}
