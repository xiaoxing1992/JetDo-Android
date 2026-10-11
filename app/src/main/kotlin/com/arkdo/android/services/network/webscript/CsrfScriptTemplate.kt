package com.arkdo.android.services.network.webscript

// ==========================================================================
// 与 HarmonyOS 版 services/network/webscript/CsrfScriptTemplate.ets 逐字对齐。
// 需要 CSRF token 的同源写请求脚本模板。
//
// 变化点五个，全部参数化；骨架一字不动——包括 header 的**顺序**。
// 顺序是承重的：CF 会看请求指纹，重排可能让写请求被拦。调用方传入的 headerLines
// 必须与原实现逐行等同，不做"排序整理"。
// ==========================================================================

data class CsrfScriptOptions(
    val requestIdToken: String,
    val urlExpr: String,
    val methodExpr: String,
    val headerLines: List<String>,
    val prelude: String,
    val bodyExpr: String,
    val referrerExpr: String
)

fun buildCsrfScript(options: CsrfScriptOptions): String =
    buildCsrfScript(
        options.requestIdToken,
        options.urlExpr,
        options.methodExpr,
        options.headerLines,
        options.prelude,
        options.bodyExpr,
        options.referrerExpr
    )

fun buildCsrfScript(
    requestIdToken: String,
    urlExpr: String,
    methodExpr: String,
    headerLines: List<String>,
    prelude: String,
    bodyExpr: String,
    referrerExpr: String
): String {
    val headers = headerLines.joinToString(",\n") { "            $it" }
    // 带 body 时 headers 的收尾花括号要跟逗号，不带时不跟——保持与原实现完全一致的字面形态。
    val bodyPart = if (bodyExpr.isNotEmpty()) ",\n          body: $bodyExpr" else ""

    return """
        (() => {
            const requestId = '$requestIdToken';
            const baseUrl = 'https://linux.do';
            const requestUrl = new URL($urlExpr, baseUrl);
            let csrfSource = 'none';
            let csrfLength = 0;
            console.log('[JetDo][CsrfScript] document.cookie=', document.cookie.substring(0, 200));
            console.log('[JetDo][CsrfScript] document.location.href=', document.location.href);
            const fail = (error) => {
              window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                statusCode: 0,
                contentType: '',
                body: '',
                errorMessage: String(error),
                csrfSource: csrfSource,
                csrfLength: csrfLength
              }));
            };
            const metaCsrf = () => {
              const el = document.querySelector('meta[name="csrf-token"]');
              return el ? (el.getAttribute('content') || '') : '';
            };
            const readCsrf = () => {
              return fetch(new URL('/session/csrf.json', baseUrl).toString(), {
                credentials: 'include',
                cache: 'no-store',
                headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
              }).then((r) => r.json()).then((j) => (j && j.csrf) ? j.csrf : '').catch(() => '');
            };
            Promise.resolve(readCsrf()).then((token) => {
              let csrf = token;
              csrfSource = token ? 'session' : 'none';
              if (!csrf) {
                csrf = metaCsrf();
                csrfSource = csrf ? 'meta' : 'none';
              }
              csrfLength = csrf ? csrf.length : 0;
              if (!csrf) {
                fail('csrf token missing');
                return null;
              }
$prelude          const fetchOptions = {
                method: $methodExpr,
                credentials: 'include',
                cache: 'no-store',
                headers: {
$headers
                }$bodyPart
              };
              const _ref = $referrerExpr;
              if (_ref) {
                fetchOptions.referrer = _ref;
                fetchOptions.referrerPolicy = 'strict-origin-when-cross-origin';
              }
              return fetch(requestUrl.toString(), fetchOptions);
            }).then((response) => {
              response.text().then((body) => {
                window.JetDoBridge.onJsonResult(requestId, JSON.stringify({
                  statusCode: response.status,
                  contentType: response.headers.get('content-type') || '',
                  cfMitigated: response.headers.get('cf-mitigated') || '',
                  body: body,
                  csrfSource: csrfSource,
                  csrfLength: csrfLength
                }));
              }).catch(fail);
            }).catch(fail);
            return requestId;
          })();
    """.trimIndent()
}
