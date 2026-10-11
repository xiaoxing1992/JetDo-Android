package com.jetdo.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * flair 角标的渲染决策。
 *
 * 这条规则对齐鸿蒙 UserAvatar.ets 的 hasFlair() / isImageFlair()：
 *  · 判"图片还是图标"看的是**地址形态**，不是"在不在图标集里"
 *  · 两边都不成立就整个不画
 *
 * 之前 Android 侧反过来判（先查图标集，查不到当图片地址去请求），
 * 结果不认识的图标名会画出一个彩色圆圈套着加载失败的图。
 */
class FlairKindTest {

    private val size = 48f

    @Test
    fun `绝对地址是图片型`() {
        assertEquals(FlairKind.IMAGE, flairKind("https://cdn.example.com/a.png", size, iconExists = false))
        assertEquals(FlairKind.IMAGE, flairKind("http://cdn.example.com/a.png", size, iconExists = false))
    }

    @Test
    fun `协议相对地址也是图片型`() {
        // linux.do 的上传资源就是这种形态。
        assertEquals(FlairKind.IMAGE, flairKind("//linuxdo-uploads.s3.ldstatic.com/a.png", size, iconExists = false))
    }

    @Test
    fun `图标集里有的名字是图标型`() {
        assertEquals(FlairKind.ICON, flairKind("shield-halved", size, iconExists = true))
    }

    /** 本次修复的核心：不认识的图标名不该被当成图片地址。 */
    @Test
    fun `图标集里没有的名字不画`() {
        assertEquals(FlairKind.NONE, flairKind("some-plugin-icon", size, iconExists = false))
    }

    @Test
    fun `空地址不画`() {
        assertEquals(FlairKind.NONE, flairKind("", size, iconExists = true))
    }

    /** 小头像（回复列表里的 20px 之类）不挂角标，否则会被糊住。 */
    @Test
    fun `头像太小不画`() {
        assertEquals(FlairKind.NONE, flairKind("https://cdn.example.com/a.png", 20f, iconExists = true))
        assertEquals(FlairKind.NONE, flairKind("shield-halved", 31f, iconExists = true))
    }

    @Test
    fun `刚好到门槛要画`() {
        assertEquals(FlairKind.ICON, flairKind("shield-halved", 32f, iconExists = true))
    }

    /** 图片地址即便同名图标不存在也照画 —— 地址形态优先于图标集。 */
    @Test
    fun `地址形态优先于图标集`() {
        assertEquals(FlairKind.IMAGE, flairKind("//x.com/a.png", size, iconExists = true))
    }
}
