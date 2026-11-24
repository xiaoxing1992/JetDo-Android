package com.jetdo.android.core.model

// ==========================================================================
// 站点微应用（mapp）领域模型。
//
// 对应站点协议：
//   GET /mini-app-api/production/<uuid>/launch
//     → { release, runtime_token, runtime_context }
//   release 内含 manifest / permissions / package_url / package_password /
//   package_size / zip_sha256 / public_key 等字段。
//
// 包不是 ZIP —— 是自定义「DMA 容器」（magic 44 4D 41 01），解密与验签在
// 论坛下发的 Worker 内完成。本模型只描述协议字段，不做解密。
//
// 参考：github.com/linux-do/mapp-developer-guide
// ==========================================================================

/** 微应用清单。字段名与站点 manifest 逐字对应。 */
data class MiniAppManifest(
    val format: Int,
    val id: String,
    val name: String,
    val version: String,
    val entry: String,
    val script: String,
    val style: String,
    val permissions: List<String>,
    val tags: List<String> = emptyList(),
    val assets: List<String> = emptyList(),
    val media: Map<String, MiniAppMedia> = emptyMap(),
    /** manifest 内声明的 HTTP 端点（名称 → 声明）。宿主只在这些端点上放行网络请求。 */
    val endpoints: Map<String, MiniAppEndpoint> = emptyMap(),
    /** manifest 内声明的 WebSocket 端点。 */
    val websockets: Map<String, MiniAppWebSocket> = emptyMap(),
    /** manifest 内声明的外链白名单（需 links.open 权限）。 */
    val links: List<String> = emptyList(),
    /** 允许调用的其它微应用 ID（需 app.call 权限）。 */
    val calls: List<String> = emptyList(),
    /** 远程素材声明（需 assets.remote 权限）。 */
    val remoteAssets: Map<String, MiniAppRemoteAsset> = emptyMap(),
    /** 可执行代码角色声明。 */
    val code: MiniAppCodeDeclarations = MiniAppCodeDeclarations(),
    /** 通知模板声明（需 notifications 权限）。 */
    val notifications: Map<String, MiniAppNotificationTemplate> = emptyMap()
)

data class MiniAppMedia(
    val path: String,
    val kind: String,
    val mime: String
)

/**
 * HTTP 端点声明。
 *
 * 官方约定（docs/sdk-network.md）：
 *  - `query` 是**允许的动态 query 键白名单**，不是默认值；调用方只能传这些键。
 *  - `auth` 三种模式：none（不带 JWT）/ platform（必须登录并带 JWT）/ auto（有登录就带）。
 *  - `headers` 是审核过的常量头，调用方不能覆盖（SDK 类型里是 `headers?: never`）。
 *  - `redirectTo` 指向另一个已审核的 GET/HEAD 端点，用于受控重定向。
 */
data class MiniAppEndpoint(
    val url: String,
    val method: String = "GET",
    /** none / platform / auto。 */
    val auth: String = "none",
    val headers: Map<String, String> = emptyMap(),
    /** 允许的动态 query 键。 */
    val query: List<String> = emptyList(),
    /** 受控重定向目标端点名。 */
    val redirectTo: String? = null
)

/** WebSocket 端点声明。子协议在 manifest 声明，不支持自定义握手头。 */
data class MiniAppWebSocket(
    val url: String,
    /** none / platform / auto。 */
    val auth: String = "none",
    /** 允许的动态 query 键。 */
    val query: List<String> = emptyList(),
    /** 声明的 WS 子协议。 */
    val protocols: List<String> = emptyList()
)

/** 通知模板（manifest.notifications）。 */
data class MiniAppNotificationTemplate(
    val title: String,
    val body: String
)

data class MiniAppRemoteAsset(
    val url: String,
    val sha256: String,
    val size: Long,
    val mime: String
)

data class MiniAppCodeDeclarations(
    val scripts: List<String> = emptyList(),
    val modules: List<String> = emptyList(),
    val workers: List<String> = emptyList(),
    val moduleBundle: String? = null,
    /** AudioWorklet 处理器路径 → SHA-256。 */
    val worklets: Map<String, String> = emptyMap()
)

/** 发布版本信息。 */
data class MiniAppRelease(
    val id: String,
    val appId: String,
    val name: String,
    val channel: String,
    val version: String,
    val releaseNotes: String?,
    val manifest: MiniAppManifest,
    /** 已批准的权限（以审核结果为准，与 manifest.permissions 可能不同）。 */
    val permissions: List<String>,
    val packageUrl: String,
    val packagePassword: String?,
    val packageSize: Long,
    val zipSha256: String,
    val publicKey: String?
)

/** 启动接口的完整响应。 */
data class MiniAppLaunch(
    val release: MiniAppRelease,
    val runtimeToken: String?,
    val runtimeContext: String?,
    /**
     * 站点原始 release 对象的 JSON 字符串（未经裁剪/重建）。
     *
     * 解密 Worker 会用包内嵌的 release 元数据做精确校验（RELEASE_MISMATCH），
     * 任何字段缺失或重排都会失败。因此必须把站点原文透传给 Worker，
     * 不能用 [release] 的结构化字段重新拼装。
     */
    val releaseRawJson: String = ""
)

/** 广场列表项。 */
data class MiniAppSummary(
    val id: String,
    val name: String,
    val description: String?,
    val iconUrl: String?,
    val version: String?,
    val tags: List<String> = emptyList(),
    val authorName: String? = null
)

/**
 * 广场列表的一页。
 *
 * 站点 `/mini-app-api/apps.json?page=N` 返回分页元数据：
 * `{apps, page, total, per_page, sort, query, favorite, tag, untagged}`。
 * 0-based，`total`/`per_page` 用于判断是否还有下一页；服务端会把越界 page
 * clamp 到最后一页，所以 [hasMore] 以 total 为准而不是看本页条数是否满。
 */
data class MiniAppListPage(
    val apps: List<MiniAppSummary>,
    val page: Int,
    val total: Int,
    val perPage: Int,
    val hasMore: Boolean
)

/**
 * 论坛用户资料快照。
 *
 * 官方 SDK 的 `user.getCurrent()` / `user.getById()` 返回同样的字段：
 * id / username / name / initials / trustLevel / avatarUrl。
 * trustLevel 为 0–4，拿不到时为 null。
 */
data class MiniAppProfile(
    val id: Long,
    val username: String,
    val name: String,
    val initials: String,
    val trustLevel: Int?,
    val avatarUrl: String?
)

/** SDK 权限全集。宿主必须逐项校验，未批准的权限一律拒绝。 */
object MiniAppPermissions {
    const val USER_READ = "user.read"
    const val USER_LOOKUP = "user.lookup"
    const val LAUNCH_READ = "launch.read"
    const val THEME_READ = "theme.read"
    const val STORAGE_READ = "storage.read"
    const val STORAGE_WRITE = "storage.write"
    const val COOKIES_READ = "cookies.read"
    const val COOKIES_WRITE = "cookies.write"
    const val CLIPBOARD_READ = "clipboard.read"
    const val CLIPBOARD_WRITE = "clipboard.write"
    const val FULLSCREEN = "fullscreen"
    const val POINTER_LOCK = "pointer.lock"
    const val AUDIO_PLAY = "audio.play"
    const val AUDIO_WEB_AUDIO = "audio.webAudio"
    const val AUDIO_WORKLET = "audio.worklet"
    const val VIDEO_PLAY = "video.play"
    const val VIDEO_STREAM = "video.stream"
    const val NETWORK_REQUEST = "network.request"
    const val NETWORK_WEBSOCKET = "network.websocket"
    const val ASSETS_REMOTE = "assets.remote"
    const val LINKS_OPEN = "links.open"
    const val FILES_SAVE = "files.save"
    const val SHARING_SHARE = "sharing.share"
    const val SCREEN_KEEP_AWAKE = "screen.keepAwake"
    const val GAMEPAD_READ = "gamepad.read"
    const val NOTIFICATIONS = "notifications"
    const val APP_CALL = "app.call"
    const val CODE_LOAD = "code.load"
    const val CODE_MODULES = "code.modules"
    const val CODE_EVAL = "code.eval"
    const val WORKERS_CREATE = "workers.create"
    const val CAMERA = "camera"
    const val MICROPHONE = "microphone"
    const val SCREEN_CAPTURE = "screen.capture"
    const val LOCATION_READ = "location.read"
    const val DEVICE_USB = "device.usb"
    const val DEVICE_BLUETOOTH = "device.bluetooth"

    /** 站点审核通过的完整权限集合（与 SDK pack 校验用的白名单一致）。 */
    val ALL: Set<String> = setOf(
        NOTIFICATIONS, APP_CALL, USER_READ, USER_LOOKUP, LAUNCH_READ, THEME_READ,
        STORAGE_READ, STORAGE_WRITE, COOKIES_READ, COOKIES_WRITE,
        CLIPBOARD_READ, CLIPBOARD_WRITE, FULLSCREEN, POINTER_LOCK,
        AUDIO_PLAY, AUDIO_WEB_AUDIO, AUDIO_WORKLET, LINKS_OPEN, FILES_SAVE,
        SHARING_SHARE, SCREEN_KEEP_AWAKE, GAMEPAD_READ,
        CODE_LOAD, WORKERS_CREATE, CODE_MODULES, CODE_EVAL, VIDEO_STREAM,
        CAMERA, MICROPHONE, SCREEN_CAPTURE, LOCATION_READ,
        DEVICE_USB, DEVICE_BLUETOOTH, VIDEO_PLAY, ASSETS_REMOTE,
        NETWORK_REQUEST, NETWORK_WEBSOCKET
    )
}

/** 宿主返回给 SDK 的错误码（与站点 SDK 约定一致）。 */
object MiniAppErrorCodes {
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val USER_CANCELLED = "USER_CANCELLED"
    const val TIMEOUT = "TIMEOUT"
    const val APP_UNLOADED = "APP_UNLOADED"
    const val ASSET_NOT_FOUND = "ASSET_NOT_FOUND"
    const val API_UNSUPPORTED = "API_UNSUPPORTED"
    const val STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE"
    const val REMOTE_ASSET_FAILED = "REMOTE_ASSET_FAILED"
    const val REMOTE_ASSET_INVALID = "REMOTE_ASSET_INVALID"
    const val RATE_LIMIT = "RATE_LIMIT"
    const val MESSAGE_LIMIT = "MESSAGE_LIMIT"
    const val BROWSER_UNSUPPORTED = "BROWSER_UNSUPPORTED"
    const val SDK_UNAVAILABLE = "SDK_UNAVAILABLE"
    const val NETWORK_FAILURE = "NETWORK_FAILURE"
    const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
    const val SOCKET_CLOSED = "SOCKET_CLOSED"
    const val APP_NOT_ALLOWED = "APP_NOT_ALLOWED"
    const val APP_NOT_IN_CALL_STACK = "APP_NOT_IN_CALL_STACK"
    const val APP_UNAVAILABLE = "APP_UNAVAILABLE"
    const val APP_NAVIGATION_UNAVAILABLE = "APP_NAVIGATION_UNAVAILABLE"
}
