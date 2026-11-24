package com.jetdo.android.core.data.miniapp

import com.jetdo.android.core.model.MiniAppCodeDeclarations
import com.jetdo.android.core.model.MiniAppEndpoint
import com.jetdo.android.core.model.MiniAppLaunch
import com.jetdo.android.core.model.MiniAppManifest
import com.jetdo.android.core.model.MiniAppMedia
import com.jetdo.android.core.model.MiniAppNotificationTemplate
import com.jetdo.android.core.model.MiniAppRelease
import com.jetdo.android.core.model.MiniAppRemoteAsset
import com.jetdo.android.core.model.MiniAppWebSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 微应用启动接口的响应解析。
 *
 * 站点返回体形状（逆向确认）：
 * ```
 * { release: { id, app_id, name, channel, version, release_notes, manifest,
 *              permissions, package_url, package_password, package_size,
 *              zip_sha256, public_key },
 *   runtime_token, runtime_context }
 * ```
 *
 * 解析刻意保守：缺字段就报失败，不猜默认值。manifest.permissions 是**申请**列表，
 * 真正生效的是 release.permissions（审核结果），宿主一律以后者为准。
 */
internal object MiniAppLaunchParser {

    fun parse(body: String): MiniAppLaunch {
        val root = Json.parseToJsonElement(body).jsonObject
        val releaseObj = root["release"]?.jsonObject
            ?: error("响应缺少 release")
        val manifestObj = releaseObj["manifest"]?.jsonObject
            ?: error("响应缺少 release.manifest")

        val manifest = parseManifest(manifestObj)
        // release.manifest 里的 permissions 是申请值；release.permissions 是批准值。
        // 后者缺失时保守退回 manifest 的申请值（站点正常都会给）。
        val granted = releaseObj["permissions"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: manifest.permissions

        val release = MiniAppRelease(
            id = releaseObj.string("id").orEmpty(),
            appId = releaseObj.string("app_id").orEmpty(),
            name = releaseObj.string("name") ?: manifest.name,
            channel = releaseObj.string("channel").orEmpty(),
            version = releaseObj.string("version") ?: manifest.version,
            releaseNotes = releaseObj.string("release_notes"),
            manifest = manifest,
            permissions = granted,
            packageUrl = releaseObj.string("package_url")
                ?: error("响应缺少 release.package_url"),
            packagePassword = releaseObj.string("package_password"),
            packageSize = releaseObj["package_size"]?.jsonPrimitive?.longOrNull ?: 0L,
            zipSha256 = releaseObj.string("zip_sha256").orEmpty(),
            publicKey = releaseObj.string("public_key")
        )

        return MiniAppLaunch(
            release = release,
            runtimeToken = root.string("runtime_token"),
            runtimeContext = root.string("runtime_context"),
            // 原文透传：Worker 的 RELEASE_MISMATCH 校验要求字段与站点下发完全一致。
            releaseRawJson = releaseObj.toString()
        )
    }

    private fun parseManifest(obj: JsonObject): MiniAppManifest {
        val permissions = obj["permissions"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()

        return MiniAppManifest(
            format = obj["format"]?.jsonPrimitive?.intOrNullSafe() ?: 1,
            id = obj.string("id").orEmpty(),
            name = obj.string("name").orEmpty(),
            version = obj.string("version").orEmpty(),
            entry = obj.string("entry") ?: "index.html",
            script = obj.string("script") ?: "app.js",
            style = obj.string("style") ?: "app.css",
            permissions = permissions,
            tags = obj.stringList("tags"),
            assets = obj.stringList("assets"),
            media = obj["media"]?.jsonObject?.entries?.associate { (key, value) ->
                val m = value.jsonObject
                key to MiniAppMedia(
                    path = m.string("path").orEmpty(),
                    kind = m.string("kind").orEmpty(),
                    mime = m.string("mime").orEmpty()
                )
            } ?: emptyMap(),
            endpoints = parseEndpoints(obj["endpoints"]),
            websockets = parseWebSockets(obj["websockets"]),
            links = obj.stringList("links"),
            calls = obj.stringList("calls"),
            remoteAssets = obj["remote_assets"]?.jsonObject?.entries?.associate { (key, value) ->
                val r = value.jsonObject
                key to MiniAppRemoteAsset(
                    url = r.string("url").orEmpty(),
                    sha256 = r.string("sha256").orEmpty(),
                    size = r["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    mime = r.string("mime").orEmpty()
                )
            } ?: emptyMap(),
            code = obj["code"]?.jsonObject?.let { c ->
                MiniAppCodeDeclarations(
                    scripts = c.stringList("scripts"),
                    modules = c.stringList("modules"),
                    workers = c.stringList("workers"),
                    moduleBundle = c.string("moduleBundle"),
                    worklets = c["worklets"]?.jsonObject?.entries?.associate { (wk, wv) ->
                        wk to ((wv as? JsonPrimitive)?.contentOrNull.orEmpty())
                    } ?: emptyMap()
                )
            } ?: MiniAppCodeDeclarations(),
            notifications = obj["notifications"]?.jsonObject?.entries?.associate { (key, value) ->
                val n = value.jsonObject
                key to MiniAppNotificationTemplate(
                    title = n.string("title").orEmpty(),
                    body = n.string("body").orEmpty()
                )
            } ?: emptyMap()
        )
    }

    private fun parseEndpoints(element: kotlinx.serialization.json.JsonElement?): Map<String, MiniAppEndpoint> {
        val obj = element?.jsonObject ?: return emptyMap()
        return obj.entries.associate { (key, value) ->
            val e = value.jsonObject
            key to MiniAppEndpoint(
                url = e.string("url").orEmpty(),
                method = e.string("method") ?: "GET",
                auth = e.string("auth") ?: "none",
                headers = e["headers"]?.jsonObject?.entries?.associate { (hk, hv) ->
                    hk to (hv as? JsonPrimitive)?.contentOrNull.orEmpty()
                } ?: emptyMap(),
                query = e["query"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                redirectTo = e.string("redirectTo")
            )
        }
    }

    private fun parseWebSockets(element: kotlinx.serialization.json.JsonElement?): Map<String, MiniAppWebSocket> {
        val obj = element?.jsonObject ?: return emptyMap()
        return obj.entries.associate { (key, value) ->
            val e = value.jsonObject
            key to MiniAppWebSocket(
                url = e.string("url").orEmpty(),
                auth = e.string("auth") ?: "none",
                query = e["query"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                protocols = e["protocols"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.contentOrNull

    private fun JsonObject.stringList(key: String): List<String> =
        this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    private fun JsonPrimitive.intOrNullSafe(): Int? =
        contentOrNull?.toIntOrNull()
}
