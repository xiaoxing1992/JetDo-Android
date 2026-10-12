package com.jetdo.android.services.network

import com.jetdo.android.services.network.webscript.buildCsrfScript

// ==========================================================================
// 与 HarmonyOS 版 services/network/ArkWebFetchScriptFactory.ets 逐字对齐。
// 为各类请求生成在 WebView 内执行的 fetch 脚本。纯字符串生成。
// 脚本内 REQUEST_ID_TOKEN 占位，由执行器运行前替换成真实 requestId。
// 写请求统一走 buildCsrfScript（带 CSRF 令牌与 Discourse 头）。
// ==========================================================================

/** 脚本里的 requestId 占位符：执行器 run() 时用真实 id 替换。 */
const val REQUEST_ID_TOKEN: String = "__JETDO_REQUEST_ID__"

object WebFetchScriptFactory {

    fun createFetchScript(path: String): String {
        val encodedPath = jsonString(path)
        return """
        (() => {
            const requestId = '$requestIdToken';
            // 硬编码域名，避免依赖 window.location.origin（页面加载失败时会是 chrome-error://）
            const baseUrl = 'https://linux.do';
            const requestUrl = new URL($encodedPath, baseUrl);
            fetch(requestUrl.toString(), {
              credentials: 'include',
              cache: 'no-store',
              headers: {
                'Accept': 'application/json',
                'Cache-Control': 'no-cache',
                'Pragma': 'no-cache'
              }
            }).then((response) => {
              response.text().then((body) => {
                window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                  statusCode: response.status,
                  contentType: response.headers.get('content-type') || '',
                  cfMitigated: response.headers.get('cf-mitigated') || '',
                  body: body
                }));
              }).catch((error) => {
                window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                  statusCode: 0,
                  contentType: '',
                  body: '',
                  errorMessage: String(error)
                }));
              });
            }).catch((error) => {
              window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                statusCode: 0,
                contentType: '',
                body: '',
                errorMessage: String(error)
              }));
            });
            return requestId;
          })();
        """.trimIndent()
    }

    /** 带自定义头 + Referer 的 GET 脚本（credit.linux.do 等副站点用）。 */
    fun createHeaderedFetchScript(
        path: String,
        headers: List<HttpHeaderEntry>,
        referrer: String
    ): String {
        val encodedPath = jsonString(path)
        val encodedReferrer = jsonString(referrer)
        val extraHeaderLines = headers
            .joinToString(",\n") { "            ${jsonString(it.name)}: ${jsonString(it.value)}" }
        val extraHeaderBlock = if (extraHeaderLines.isNotEmpty()) ",\n$extraHeaderLines" else ""

        return """
        (() => {
            const requestId = '$requestIdToken';
            const baseUrl = 'https://linux.do';
            const requestUrl = new URL($encodedPath, baseUrl);
            const fail = (error) => {
              window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                statusCode: 0,
                contentType: '',
                body: '',
                errorMessage: String(error)
              }));
            };
            const fetchOptions = {
              credentials: 'include',
              cache: 'no-store',
              headers: {
                'Accept': 'application/json, text/plain, */*',
                'Cache-Control': 'no-cache',
                'Pragma': 'no-cache'$extraHeaderBlock
              }
            };
            const _ref = $encodedReferrer;
            if (_ref) {
              fetchOptions.referrer = _ref;
              fetchOptions.referrerPolicy = 'strict-origin-when-cross-origin';
            }
            fetch(requestUrl.toString(), fetchOptions).then((response) => {
              response.text().then((body) => {
                window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                  statusCode: response.status,
                  contentType: response.headers.get('content-type') || '',
                  cfMitigated: response.headers.get('cf-mitigated') || '',
                  body: body
                }));
              }).catch(fail);
            }).catch(fail);
            return requestId;
          })();
        """.trimIndent()
    }

    /**
     * 二进制取图脚本：fetch 头像 → arrayBuffer → base64（分块 fromCharCode 避免大数组爆栈）。
     * 跨源必须 credentials:'omit'（CDN 回 ACAO 无 Allow-Credentials），同源仍 include。
     */
    fun createImageFetchScript(target: String): String {
        val encodedTarget = jsonString(target)
        return """
        (() => {
            const requestId = '$requestIdToken';
            const baseUrl = 'https://linux.do';
            const requestUrl = new URL($encodedTarget, baseUrl);
            const sameOrigin = requestUrl.origin === baseUrl;
            const fail = (error) => {
              window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                statusCode: 0, contentType: '', body: '', errorMessage: String(error)
              }));
            };
            fetch(requestUrl.toString(), {
              credentials: sameOrigin ? 'include' : 'omit',
              cache: 'default',
              headers: { 'Accept': 'image/*' }
            }).then((response) => {
              response.arrayBuffer().then((buf) => {
                const bytes = new Uint8Array(buf);
                let binary = '';
                const chunk = 8192;
                for (let i = 0; i < bytes.length; i += chunk) {
                  binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
                }
                window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                  statusCode: response.status,
                  contentType: response.headers.get('content-type') || '',
                  body: btoa(binary)
                }));
              }).catch(fail);
            }).catch(fail);
            return requestId;
          })();
        """.trimIndent()
    }

    /** 表单写请求（timings/点赞/投票等）。X-SILENCE-LOGGER + Discourse-Background 是后台静默标记，勿删勿重排。 */
    fun createFormScript(method: String, path: String, body: String, referrer: String): String =
        buildCsrfScript(
            requestIdToken = REQUEST_ID_TOKEN,
            urlExpr = jsonString(path),
            methodExpr = jsonString(method),
            headerLines = listOf(
                "'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'",
                "'Accept': '*/*'",
                "'X-CSRF-Token': csrf",
                "'X-Requested-With': 'XMLHttpRequest'",
                "'X-SILENCE-LOGGER': 'true'",
                "'Discourse-Background': 'true'",
                "'Discourse-Present': 'true'",
                "'Discourse-Logged-In': 'true'"
            ),
            prelude = "",
            // 空 body 必须省略，不能发 body: ""。fetch 对 DELETE/PUT 带上即使是空串的 body，
            // 也会强制 Content-Type 并改写请求指纹；Discourse 把这种 DELETE /posts/:id
            // 判成 invalid_access（「not permitted to view」），而不是 can_delete 失败。
            bodyExpr = if (body.isEmpty()) "" else jsonString(body),
            referrerExpr = jsonString(referrer)
        )

    /** 发表评论/回复：POST /posts，application/json。前台请求，不带 X-SILENCE-LOGGER / Discourse-Background。 */
    fun createPostJsonScript(path: String, body: String, referrer: String): String =
        buildCsrfScript(
            requestIdToken = REQUEST_ID_TOKEN,
            urlExpr = jsonString(path),
            methodExpr = "'POST'",
            headerLines = listOf(
                "'Content-Type': 'application/json'",
                "'Accept': '*/*'",
                "'X-CSRF-Token': csrf",
                "'X-Requested-With': 'XMLHttpRequest'",
                "'Discourse-Present': 'true'",
                "'Discourse-Logged-In': 'true'"
            ),
            prelude = "",
            bodyExpr = jsonString(body),
            referrerExpr = jsonString(referrer)
        )

    /**
     * 撤销 User API Key：POST /user-api-key/revoke。
     * key 自证身份（User-Api-Key 头），Rails 侧**不需要 CSRF 也不需要会话**——浏览器授权
     * 兑换成功后「用完即焚」走这里，此时 App 还没登录，所以不能走 buildCsrfScript。
     *
     * ⚠️ 但 Cloudflare 侧需要 cookie：真机实测（CDP header/credentials 矩阵）——
     * `credentials: 'omit'` 一律回 403 + `cf-mitigated: challenge`（Just a moment...）；
     * 换成 `'include'` 后同一请求直接穿过，服务端回 Rails JSON
     * `{"errors":["您没有权限查看请求的资源。"],"error_type":"invalid_access"}`
     * （403 是因为实验用的是假 key，重点是**请求已到 Rails**）。
     * "不依赖会话"是 Rails 的语义，不等于"不带 cookie"——带上是纯赚，CF 就认这个。
     * 原先用 omit 导致焚毁静默失败：出示端轮询按 key 存活判断「已被扫」，
     * key 烧不掉就一直显示"等待扫描"。
     */
    fun createUserApiKeyRevokeScript(key: String): String {
        val encodedKey = jsonString(key)
        return """
        (() => {
            const requestId = '$requestIdToken';
            const baseUrl = 'https://linux.do';
            fetch(baseUrl + '/user-api-key/revoke', {
              method: 'POST',
              credentials: 'include',
              cache: 'no-store',
              headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                'User-Api-Key': $encodedKey,
                'X-Requested-With': 'XMLHttpRequest',
                'Discourse-Present': 'true',
                'Discourse-Logged-In': 'true'
              }
            }).then((response) => {
              response.text().then((body) => {
                window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                  statusCode: response.status,
                  contentType: response.headers.get('content-type') || '',
                  body: body
                }));
              }).catch((error) => {
                window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                  statusCode: 0, body: '', errorMessage: String(error)
                }));
              });
            }).catch((error) => {
              window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                statusCode: 0, body: '', errorMessage: String(error)
              }));
            });
            return requestId;
          })();
        """.trimIndent()
    }

    /**
     * 扫码登录出示端：POST /user-api-key 创建一枚跨设备分享 key（会话 CSRF）。
     * 必须带 Accept json + X-Requested-With——服务端对 XHR 返回 200 JSON
     * {redirect_url}；若按 HTML 协商会 302 到 discourse://，fetch 跟不动（scheme 不支持），
     * 那条路在 WebView 里走不通，只能指望 JSON 路径（FluxDO 同款约束）。
     *
     * ⚠️ Discourse-Present / Discourse-Logged-In 是本端点的**过盾必需品**，不是可选装饰：
     * 真机实测（CDP 在宿主 WebView 里做 header 变体矩阵）——只带 Content-Type/Accept/
     * X-CSRF-Token/X-Requested-With 时，Cloudflare 对 POST /user-api-key 一律回
     * 403 + `cf-mitigated: challenge`（Just a moment... 挑战页，请求根本没到 Rails）；
     * 补上这两个头后同一个请求直接穿过，服务端回 `["BAD CSRF"]`（text/plain）——
     * 即已进 Rails 的 CSRF 校验。本文件其余 4 个写脚本一直带着它们，只有本脚本漏了，
     * 表现就是"出码恒失败 403"，与是否登录、是否过盾都无关。
     */
    fun createUserApiKeyShareScript(formBody: String, referrer: String): String =
        buildCsrfScript(
            requestIdToken = REQUEST_ID_TOKEN,
            urlExpr = jsonString("/user-api-key"),
            methodExpr = "'POST'",
            headerLines = listOf(
                "'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'",
                "'Accept': 'application/json, text/javascript, */*; q=0.01'",
                "'X-CSRF-Token': csrf",
                "'X-Requested-With': 'XMLHttpRequest'",
                "'Discourse-Present': 'true'",
                "'Discourse-Logged-In': 'true'"
            ),
            prelude = "",
            bodyExpr = jsonString(formBody),
            referrerExpr = jsonString(referrer)
        )

    /** 图片上传：base64 → Uint8Array → Blob → FormData。刻意不设 Content-Type（浏览器写 multipart boundary）。 */
    fun createUploadScript(base64: String, fileName: String, mimeType: String, referrer: String): String {
        val encodedBase64 = jsonString(base64)
        val encodedFileName = jsonString(fileName)
        val encodedMimeType = jsonString(mimeType)
        return buildCsrfScript(
            requestIdToken = REQUEST_ID_TOKEN,
            urlExpr = "'/uploads.json'",
            methodExpr = "'POST'",
            headerLines = listOf(
                "'Accept': '*/*'",
                "'X-CSRF-Token': csrf",
                "'X-Requested-With': 'XMLHttpRequest'",
                "'Discourse-Present': 'true'",
                "'Discourse-Logged-In': 'true'"
            ),
            prelude = """
                const binary = atob($encodedBase64);
                const len = binary.length;
                const bytes = new Uint8Array(len);
                for (let i = 0; i < len; i++) { bytes[i] = binary.charCodeAt(i); }
                const blob = new Blob([bytes], { type: $encodedMimeType });
                const fd = new FormData();
                fd.append('upload_type', 'composer');
                fd.append('relativePath', 'null');
                fd.append('name', $encodedFileName);
                fd.append('type', $encodedMimeType);
                fd.append('file', blob, $encodedFileName);
            """.trimIndent() + "\n",
            bodyExpr = "fd",
            referrerExpr = jsonString(referrer)
        )
    }

    /** 空 body 的 PUT（书签等）。不带 body，故 headers 收尾无逗号。 */
    fun createPutScript(path: String, referrer: String): String =
        buildCsrfScript(
            requestIdToken = REQUEST_ID_TOKEN,
            urlExpr = jsonString(path),
            methodExpr = "'PUT'",
            headerLines = listOf(
                "'Accept': '*/*'",
                "'X-CSRF-Token': csrf",
                "'X-Requested-With': 'XMLHttpRequest'",
                "'X-SILENCE-LOGGER': 'true'",
                "'Discourse-Background': 'true'",
                "'Discourse-Present': 'true'",
                "'Discourse-Logged-In': 'true'"
            ),
            prelude = "",
            bodyExpr = "",
            referrerExpr = jsonString(referrer)
        )

    private val requestIdToken: String
        get() = REQUEST_ID_TOKEN

    /** JSON.stringify 等价：给字符串加双引号并转义。 */
    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
