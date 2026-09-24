package com.arkdo.android.services.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectLevelParserTest {

    @Test
    fun parseReturnsNullWhenPageNotReady() {
        assertNull(ConnectLevelParser.parse(null))
        assertNull(ConnectLevelParser.parse(""))
        assertNull(ConnectLevelParser.parse("null"))
        assertNull(ConnectLevelParser.parse("\"\""))
        assertNull(ConnectLevelParser.parse("not json"))
    }

    @Test
    fun parseUnwrapsEvaluateJavascriptStringEncoding() {
        val inner = """{"title":"信任级别要求","statusLabel":"已达标","statusIsMet":true,"subtitle":"距离下一级","rings":[{"label":"浏览","current":"45","target":"/ 50","val":45,"max":50,"met":false}],"bars":[],"quotas":[],"vetos":[],"hint":"提示","conclusion":"尚未满足","conclusionIsMet":false}"""
        val wrapped = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<String>(),
            inner
        )

        val parsed = ConnectLevelParser.parse(wrapped)

        assertEquals("信任级别要求", parsed?.title)
        assertEquals("已达标", parsed?.statusLabel)
        assertTrue(parsed?.statusIsMet == true)
        assertEquals("距离下一级", parsed?.subtitle)
        assertEquals(1, parsed?.rings?.size)
        val ring = parsed!!.rings.single()
        assertEquals("浏览", ring.label)
        assertEquals("45", ring.current)
        assertEquals("/ 50", ring.target)
        assertEquals(45.0, ring.val_, 0.0)
        assertEquals(50.0, ring.max, 0.0)
        assertFalse(ring.met)
        assertEquals("提示", parsed.hint)
        assertEquals("尚未满足", parsed.conclusion)
        assertFalse(parsed.conclusionIsMet)
    }

    @Test
    fun parseFillsMissingFieldsAndDropsUnknowns() {
        val raw = """{"rings":[{}],"bars":[{"label":"回复","nums":"157/10","val":157,"max":10,"met":true}],"quotas":[{"slots":"3","used":1}],"vetos":[{"met":false,"value":"2"}],"extra":1}"""

        val parsed = ConnectLevelParser.parse(raw)!!

        assertEquals("信任级别要求", parsed.title)
        assertEquals("", parsed.statusLabel)
        assertFalse(parsed.statusIsMet)
        assertEquals("", parsed.rings.single().label)
        assertEquals(0.0, parsed.rings.single().val_, 0.0)
        assertTrue(parsed.bars.single().met)
        assertEquals(3, parsed.quotas.single().slots)
        assertEquals("2", parsed.vetos.single().value)
        assertFalse(parsed.vetos.single().met)
    }

    @Test
    fun parseRequiresRingsArray() {
        assertNull(ConnectLevelParser.parse("""{"title":"信任级别要求"}"""))
    }
}
