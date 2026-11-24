package com.jetdo.android.core.data.miniapp

import com.jetdo.android.core.model.MiniAppPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微应用启动响应解析测试。
 *
 * 这层是协议边界：站点返回体的字段名一旦变，解析必须立刻失败而不是
 * 静默给出空值 —— 后者会让宿主拿着空 manifest 去跑，表现是微应用
 * 白屏且没有任何日志，比直接报错难查得多。
 */
class MiniAppLaunchParserTest {

    private val fullBody = """
        {
          "release": {
            "id": "rel_123",
            "app_id": "019a2a58-0000-7000-8000-000000000001",
            "name": "2048",
            "channel": "production",
            "version": "1.1.0",
            "release_notes": "首个正式版本",
            "manifest": {
              "format": 1,
              "id": "019a2a58-0000-7000-8000-000000000001",
              "name": "2048",
              "version": "1.1.0",
              "entry": "index.html",
              "script": "app.js",
              "style": "app.css",
              "permissions": ["user.read", "theme.read", "storage.read", "storage.write"],
              "tags": ["游戏", "益智"],
              "assets": ["data/settings.json"],
              "media": {},
              "endpoints": {
                "lookup": {
                  "url": "https://api.example.com/lookup",
                  "method": "POST",
                  "auth": "none",
                  "headers": { "X-App": "jetdo" },
                  "query": ["key"]
                }
              }
            },
            "permissions": ["user.read", "theme.read"],
            "package_url": "https://cdn.example.com/pkg/app.zip",
            "package_password": "s3cret",
            "package_size": 20480,
            "zip_sha256": "abc123",
            "public_key": "-----BEGIN PUBLIC KEY-----"
          },
          "runtime_token": "rt_xxx",
          "runtime_context": "ctx_yyy"
        }
    """.trimIndent()

    @Test
    fun `解析出 release 基本字段`() {
        val launch = MiniAppLaunchParser.parse(fullBody)
        val release = launch.release

        assertEquals("rel_123", release.id)
        assertEquals("019a2a58-0000-7000-8000-000000000001", release.appId)
        assertEquals("2048", release.name)
        assertEquals("production", release.channel)
        assertEquals("1.1.0", release.version)
        assertEquals("首个正式版本", release.releaseNotes)
        assertEquals("https://cdn.example.com/pkg/app.zip", release.packageUrl)
        assertEquals("s3cret", release.packagePassword)
        assertEquals(20480L, release.packageSize)
        assertEquals("abc123", release.zipSha256)
        assertEquals("rt_xxx", launch.runtimeToken)
        assertEquals("ctx_yyy", launch.runtimeContext)
    }

    @Test
    fun `解析 manifest 结构`() {
        val manifest = MiniAppLaunchParser.parse(fullBody).release.manifest

        assertEquals(1, manifest.format)
        assertEquals("index.html", manifest.entry)
        assertEquals("app.js", manifest.script)
        assertEquals("app.css", manifest.style)
        assertEquals(listOf("游戏", "益智"), manifest.tags)
        assertEquals(listOf("data/settings.json"), manifest.assets)
    }

    @Test
    fun `解析端点声明`() {
        val manifest = MiniAppLaunchParser.parse(fullBody).release.manifest
        val endpoint = manifest.endpoints["lookup"]

        assertNotNull(endpoint)
        assertEquals("https://api.example.com/lookup", endpoint?.url)
        assertEquals("POST", endpoint?.method)
        assertEquals("none", endpoint?.auth)
        assertEquals(mapOf("X-App" to "jetdo"), endpoint?.headers)
        assertEquals(listOf("key"), endpoint?.query)
    }

    @Test
    fun `已批准权限以 release 为准而非 manifest 申请值`() {
        val launch = MiniAppLaunchParser.parse(fullBody)

        // manifest 申请了 4 项，审核只批了 2 项 —— 宿主必须按批准的 2 项放行。
        assertEquals(listOf("user.read", "theme.read"), launch.release.permissions)
        assertTrue(MiniAppPermissions.STORAGE_READ !in launch.release.permissions)
    }

    @Test
    fun `缺少 release 时解析失败`() {
        val error = runCatching { MiniAppLaunchParser.parse("""{"foo":1}""") }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun `缺少 package_url 时解析失败`() {
        val body = """
            {"release":{"id":"r","app_id":"a","manifest":{"format":1,"id":"a","name":"x",
            "version":"1.0.0","entry":"index.html","script":"app.js","style":"app.css",
            "permissions":[]}}}
        """.trimIndent()
        val error = runCatching { MiniAppLaunchParser.parse(body) }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun `release 缺 permissions 时退回 manifest 申请值`() {
        val body = """
            {"release":{"id":"r","app_id":"a","name":"n","version":"1.0.0",
            "manifest":{"format":1,"id":"a","name":"x","version":"1.0.0","entry":"index.html",
            "script":"app.js","style":"app.css","permissions":["user.read"]},
            "package_url":"https://cdn.example.com/a.zip"}}
        """.trimIndent()
        val launch = MiniAppLaunchParser.parse(body)
        assertEquals(listOf("user.read"), launch.release.permissions)
        assertNull(launch.release.publicKey)
    }

    @Test
    fun `解析 websocket 与远程素材与通知模板声明`() {
        val body = """
            {"release":{"id":"r","app_id":"a","name":"n","version":"1.0.0",
            "manifest":{"format":1,"id":"a","name":"x","version":"1.0.0","entry":"index.html",
            "script":"app.js","style":"app.css","permissions":[],
            "websockets":{"events":{"url":"wss://api.example.com/events","auth":"platform",
              "query":["room"],"protocols":["json"]}},
            "remote_assets":{"images/logo.png":{"url":"https://cdn.example.com/logo.png",
              "sha256":"aa","size":1024,"mime":"image/png"}},
            "notifications":{"reminder":{"title":"%app_name% 提醒","body":"到点了"}}},
            "package_url":"https://cdn.example.com/a.zip"}}
        """.trimIndent()
        val manifest = MiniAppLaunchParser.parse(body).release.manifest

        val ws = manifest.websockets["events"]
        assertNotNull(ws)
        assertEquals("wss://api.example.com/events", ws?.url)
        assertEquals("platform", ws?.auth)
        assertEquals(listOf("room"), ws?.query)
        assertEquals(listOf("json"), ws?.protocols)

        val asset = manifest.remoteAssets["images/logo.png"]
        assertNotNull(asset)
        assertEquals("https://cdn.example.com/logo.png", asset?.url)
        assertEquals(1024L, asset?.size)
        assertEquals("image/png", asset?.mime)

        assertEquals("%app_name% 提醒", manifest.notifications["reminder"]?.title)
    }

    /**
     * 权限全集必须与官方 SDK 的白名单逐字一致。
     *
     * 少一个 → 宿主会拒绝官方允许的能力；多一个 → 审核外的权限可能被误放行。
     */
    @Test
    fun `权限全集与官方 SDK 白名单一致`() {
        val official = listOf(
            "notifications", "app.call", "user.read", "user.lookup", "launch.read",
            "theme.read", "storage.read", "storage.write", "cookies.read", "cookies.write",
            "clipboard.read", "clipboard.write", "fullscreen", "pointer.lock",
            "audio.play", "audio.webAudio", "audio.worklet", "video.play", "assets.remote",
            "network.request", "network.websocket", "links.open", "files.save",
            "sharing.share", "screen.keepAwake", "gamepad.read", "code.load",
            "workers.create", "code.modules", "code.eval", "video.stream", "camera",
            "microphone", "screen.capture", "location.read", "device.usb", "device.bluetooth"
        )
        assertEquals(official.size, MiniAppPermissions.ALL.size)
        assertEquals(official.toSet(), MiniAppPermissions.ALL)
    }
}
