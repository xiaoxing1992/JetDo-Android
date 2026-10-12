package com.jetdo.android.common.constants

// ==========================================================================
// 与 HarmonyOS 版 common/constants/AppConstants.ets 逐值对齐。
// ==========================================================================
object AppConstants {
    const val APP_NAME = "JetDo"
    const val BASE_URL = "https://linux.do"

    // 站点静态资源 CDN：主站域名在 TLS 层被按 SNI 阻断，原生请求取不到图。
    // 该 CDN 镜像同一份静态资源且不被拦，站内相对的静态图（emoji 等）一律挂这里。
    const val STATIC_CDN_BASE = "https://cdn.ldstatic.com"

    // host webview 引导页：只需一个 linux.do 同源文档（定 origin、带 cookie、供注入 fetch）。
    const val HOST_BOOTSTRAP_URL = "https://linux.do/session/csrf.json"

    // connect.linux.do：跨域，经 SSO 复用 linux.do 会话自动登录。等级进度页专用。
    const val CONNECT_URL = "https://connect.linux.do/"

    // credit.linux.do（LINUX DO Credit）：跨域，在 CF 后面，API 只能由 credit 同源文档内 fetch 发起。
    const val CREDIT_BASE_URL = "https://credit.linux.do"
    const val CREDIT_BOOTSTRAP_URL = "https://credit.linux.do/api/v1/oauth/user-info"
    const val CREDIT_HOME_URL = "https://credit.linux.do/home"

    // credit 登录态凭证（经 LinuxDO OAuth 换取）。只判存在性，值不落代码、不落日志。
    const val CREDIT_SESSION_COOKIE = "linux_do_credit_session_id"

    const val LOGIN_PATH = "/login"
    const val LOGIN_URL = "https://linux.do/login"
    const val CHALLENGE_PATH = "/challenge"
    const val CHALLENGE_URL = "https://linux.do/challenge"

    // 反馈与建议：站内反馈帖 https://linux.do/t/topic/2590051
    const val FEEDBACK_TOPIC_ID = 2590051L

    const val DEFAULT_TIMEOUT_MS = 15_000L

    /**
     * 「脚本内部两次串行 fetch」的写请求的等待窗口 —— **故意比 [DEFAULT_TIMEOUT_MS] 宽**。
     *
     * 通用闸门是按「单次请求」定的，而 buildCsrfScript 骨架拼出来的脚本内部是两次串行
     * 网络往返：先 `GET /session/csrf.json` 取 CSRF token，再发主请求。宿主只要那一刻
     * 还在 CF 挑战里，任一次 fetch 都可能被挂住，两次叠加轻松吃掉 15 秒 —— 用户看到的就是
     * kotlinx 的英文原文 `Timed out waiting for 15000 ms`（创建分享 key / OTP 兑换都踩过）。
     *
     * 项目里 upload 吃过同一个亏（见 ArkWebNetworkBridge 的 UPLOAD_TIMEOUT_MS：15s 闸门
     * 误杀 320KB 的截图上传）。这类调用点都是**用户主动触发后的前台等待**、有明确 loading 态，
     * 宽一点无妨；真正的失败仍由服务端响应给出，不靠兜底超时。
     */
    const val AUTH_WRITE_TIMEOUT_MS = 45_000L

    // host webview 因 session 变化被重载时，注入式 fetch 排队等待 onPageEnd 的兜底超时。
    const val HOST_RELOAD_READY_TIMEOUT_MS = 5_000L

    // 仅作 UserAgentProvider 取不到系统默认 UA 时的回退值。
    // 运行时 UA 由 services/web/UserAgentProvider 从真实内核生成(版本必须与引擎一致,
    // 否则 CF 指纹判定不一致导致盾频繁);cf_clearance 与签发时 UA 绑定,全 app 统一取用。
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    const val MIME_JSON = "application/json"
}
