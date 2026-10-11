package com.jetdo.android.services.content

import com.jetdo.android.common.constants.AppConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情目录（`GET /emojis.json` 的解析与查询）单测。
 *
 * 重点钉两件事：
 * 1. **相对 url 的补全规则**——标准 twemoji 是站内相对路径（要挂 CDN，主站域名在 TLS 层被
 *    按 SNI 阻断），自定义表情是协议相对的绝对地址（只补协议）。补错了表现是"表情全裂"，
 *    而且只在真机上才看得见。
 * 2. **坏数据不掀桌**——真实响应里偶有非数组的组、缺 name/url 的条目；一处不合规丢掉整张表
 *    的话，用户的整个表情面板就空了。
 */
class EmojiCatalogRegistryTest {

    @After
    fun tearDown() {
        // 目录是全局单例状态，用例之间必须清干净，否则执行顺序会影响结果。
        resetEmojiUrls()
    }

    private val sampleJson = """
        {
          "smileys_&_emotion": [
            {"name":"grinning_face","tonable":false,"url":"/images/emoji/twemoji/grinning_face.png?v=15"},
            {"name":"smile","tonable":false,"url":"/images/emoji/twemoji/smile.png?v=15"}
          ],
          "custom": [
            {"name":"tieba_087","url":"//linuxdo-uploads.s3.ldstatic.com/original/3X/x.png","created_by":"someone"},
            {"name":"already_absolute","url":"https://example.com/emoji/abs.png"}
          ]
        }
    """.trimIndent()

    @Test
    fun `装载后按短名可查到 url,计数与就绪标志同步`() {
        val count = loadEmojiUrlsFromJson(sampleJson)

        assertEquals(4, count)
        assertEquals(4, emojiCount())
        assertTrue(hasLoadedEmojiUrls())
        assertEquals(
            "${AppConstants.STATIC_CDN_BASE}/images/emoji/twemoji/smile.png?v=15",
            getEmojiUrl("smile")
        )
    }

    @Test
    fun `站内相对路径挂 CDN,协议相对补 https,绝对地址原样`() {
        loadEmojiUrlsFromJson(sampleJson)

        // 标准 twemoji：站内相对 → 挂 CDN（主站域名取不到图）。
        assertEquals(
            "${AppConstants.STATIC_CDN_BASE}/images/emoji/twemoji/grinning_face.png?v=15",
            getEmojiUrl("grinning_face")
        )
        // 自定义表情：协议相对 → 补 https。
        assertEquals(
            "https://linuxdo-uploads.s3.ldstatic.com/original/3X/x.png",
            getEmojiUrl("tieba_087")
        )
        // 已是绝对地址 → 不动。
        assertEquals("https://example.com/emoji/abs.png", getEmojiUrl("already_absolute"))
    }

    @Test
    fun `分组保留站点给的顺序与组名,空组不入列`() {
        loadEmojiUrlsFromJson(
            """
            {
              "first": [{"name":"a","url":"/a.png"}],
              "empty": [],
              "not_an_array": 42,
              "second": [{"name":"b","url":"/b.png"}]
            }
            """.trimIndent()
        )

        val groups = getEmojiGroups()
        assertEquals(listOf("first", "second"), groups.map { it.key })
        assertEquals(listOf("a"), groups[0].items.map { it.name })
    }

    @Test
    fun `缺 name 或 url 的条目被跳过,不拖垮其它条目`() {
        val count = loadEmojiUrlsFromJson(
            """
            {
              "g": [
                {"name":"ok","url":"/ok.png"},
                {"url":"/no-name.png"},
                {"name":"no-url"},
                {"name":"","url":"/empty-name.png"},
                {}
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, count)
        assertEquals("ok", getEmojiGroups().single().items.single().name)
    }

    @Test
    fun `非对象或坏 JSON 时留空表但标记已就绪`() {
        assertEquals(0, loadEmojiUrlsFromJson("[]"))
        assertEquals(0, loadEmojiUrlsFromJson("not json at all"))
        // 「已就绪但空」和「还没拉」是两种状态：前者调用方无需重试，后者要重试。
        assertTrue(hasLoadedEmojiUrls())
        assertEquals(0, emojiCount())
        assertNull(getEmojiUrl("smile"))
    }

    @Test
    fun `created_by 只在自定义表情上有值`() {
        loadEmojiUrlsFromJson(sampleJson)

        assertEquals("someone", getEmojiItem("tieba_087")?.createdBy)
        assertNull(getEmojiItem("smile")?.createdBy)
        assertEquals("custom", getEmojiItem("tieba_087")?.groupKey)
    }

    @Test
    fun `isKnownEmoji 与 getEmojiUrl 同步,空名字一律查不到`() {
        loadEmojiUrlsFromJson(sampleJson)

        assertTrue(isKnownEmoji("smile"))
        assertFalse(isKnownEmoji("nope"))
        assertFalse(isKnownEmoji(""))
        assertNull(getEmojiUrl(null))
        assertNull(getEmojiUrl(""))
    }

    @Test
    fun `重新装载是替换而不是累加`() {
        loadEmojiUrlsFromJson(sampleJson)
        assertEquals(4, emojiCount())

        loadEmojiUrlsFromJson("""{"only":[{"name":"x","url":"/x.png"}]}""")
        assertEquals(1, emojiCount())
        assertNull(getEmojiUrl("smile"))
    }

    @Test
    fun `重置后回到未就绪状态`() {
        loadEmojiUrlsFromJson(sampleJson)
        resetEmojiUrls()

        assertEquals(0, emojiCount())
        assertFalse(hasLoadedEmojiUrls())
        assertTrue(getEmojiGroups().isEmpty())
    }
}
