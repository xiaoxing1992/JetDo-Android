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
