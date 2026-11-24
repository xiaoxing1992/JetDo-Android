package com.jetdo.android.services.miniapp

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 微应用本地存储。
 *
 * 按 appId 隔离（站点约定：正式版与沙箱版互不可见），落在应用私有目录。
 * 站点 SDK 语义是「按应用与环境隔离的浏览器存储」，这里用文件 + JSON 近似实现。
 */
class MiniAppStorage(context: Context) {

    private val appContext = context.applicationContext

    /** 取某个微应用的存储文件路径。appId 为空时返回 null（无会话）。 */
    private fun fileFor(appId: String?): File? {
        if (appId.isNullOrBlank()) return null
        val dir = File(appContext.filesDir, "miniapp").apply { mkdirs() }
        // appId 是 UUID，文件名安全；仍做一次字符收敛，防止异常 id 越出目录。
        val safe = appId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "$safe.json")
    }

    /** 当前生效的 appId。由宿主运行时在会话建立时写入。 */
    @Volatile
    var currentAppId: String? = null

    fun read(key: String): String? {
        val file = fileFor(currentAppId) ?: return null
        if (!file.exists()) return null
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root[key]?.let { (it as? JsonPrimitive)?.content }
        }.getOrNull()
    }

    fun write(key: String, value: String) {
        val file = fileFor(currentAppId) ?: return
        val current = readAll().toMutableMap()
        current[key] = value
        persist(file, current)
    }

    fun remove(key: String) {
        val file = fileFor(currentAppId) ?: return
        val current = readAll().toMutableMap()
        current.remove(key)
        persist(file, current)
    }

    fun clear() {
        val file = fileFor(currentAppId) ?: return
        runCatching { file.delete() }
    }

    fun export(): Map<String, String> = readAll()

    fun import(entries: List<Pair<String?, String?>>, replace: Boolean) {
        val file = fileFor(currentAppId) ?: return
        val current = if (replace) mutableMapOf() else readAll().toMutableMap()
        entries.forEach { (k, v) ->
            if (k != null && v != null) current[k] = v
        }
        persist(file, current)
    }

    private fun readAll(): Map<String, String> {
        val file = fileFor(currentAppId) ?: return emptyMap()
        if (!file.exists()) return emptyMap()
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.content.orEmpty()
            }
        }.getOrDefault(emptyMap())
    }

    private fun persist(file: File, data: Map<String, String>) {
        runCatching {
            val json = JsonObject(
                data.mapValues { (_, v) ->
                    JsonPrimitive(v)
                }
            )
            file.writeText(json.toString())
        }
    }
}
