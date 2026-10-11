package com.jetdo.android.services.content

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 链接路由决策，对齐鸿蒙 LinkRouter.ets（resolve + isSameHost + 保留段）。
 * fixture 取自 linux.do 真实楼层 cooked 里出现过的全部链接形态。
 */
class LinkRouterTest {

    @Test
    fun `站内帖子链接四种形态都走 TOPIC`() {
        assertEquals(
            LinkRoute.Topic(topicId = 188585L, targetPostNumber = null),
            resolveLinkRoute("https://linux.do/t/topic/188585")
        )
        assertEquals(
            LinkRoute.Topic(topicId = 293017L, targetPostNumber = null),
            resolveLinkRoute("https://linux.do/t/some-slug/293017")
        )
        // 旧链接形态：/t/{topicId}（无 slug 段）
        assertEquals(
            LinkRoute.Topic(topicId = 12345L, targetPostNumber = null),
            resolveLinkRoute("https://linux.do/t/12345")
        )
        assertEquals(
            LinkRoute.Topic(topicId = 503400L, targetPostNumber = 12),
            resolveLinkRoute("https://linux.do/t/topic/503400/12")
        )
    }

    @Test
    fun `附件下载链接走 DOWNLOAD`() {
        assertEquals(
            LinkRoute.Download("https://linux.do/uploads/short-url/abc.3g.zip"),
            resolveLinkRoute("https://linux.do/uploads/short-url/abc.3g.zip")
        )
        assertEquals(
            LinkRoute.Download("https://linux.do/uploads/default/x.png"),
            resolveLinkRoute("https://linux.do/uploads/default/x.png")
        )
    }

    @Test
    fun `用户主页链接走 USER`() {
        assertEquals(
            LinkRoute.User("https://linux.do/u/neo", "neo"),
            resolveLinkRoute("https://linux.do/u/neo")
        )
        // @提及的子页（activity/summary）也归 USER。
        assertEquals(
            LinkRoute.User("https://linux.do/u/neo/activity", "neo"),
            resolveLinkRoute("https://linux.do/u/neo/activity")
        )
    }

    @Test
    fun `用户路径保留段走 WEB 不当用户主页`() {
        // 注册激活、改密、邮箱确认等保留段仍走 WebView。
        for (segment in listOf("account-created", "activate-account", "password-reset", "email-login")) {
            assertEquals(
                LinkRoute.Web("https://linux.do/u/$segment"),
                resolveLinkRoute("https://linux.do/u/$segment")
            )
        }
    }

    @Test
    fun `非法字符集不算用户名`() {
        // 用户名字符集为字母数字 . _ -，其余（如中文段）不算用户名 → WEB。
        assertEquals(
            LinkRoute.Web("https://linux.do/u/中文用户"),
            resolveLinkRoute("https://linux.do/u/中文用户")
        )
    }

    @Test
    fun `同域非帖子页与外站都走 WEB`() {
        assertEquals(
            LinkRoute.Web("https://linux.do/faq"),
            resolveLinkRoute("https://linux.do/faq")
        )
        assertEquals(
            LinkRoute.Web("https://linux.do/about"),
            resolveLinkRoute("https://linux.do/about")
        )
        assertEquals(
            LinkRoute.Web("https://linux.do/tag/%E9%9B%86%E4%B8%AD%E5%B8%96"),
            resolveLinkRoute("https://linux.do/tag/%E9%9B%86%E4%B8%AD%E5%B8%96")
        )
        // 外站：不同 host（connect.linux.do / ldcstore.com）不参与站内识别。
        assertEquals(
            LinkRoute.Web("https://connect.linux.do/dash/email"),
            resolveLinkRoute("https://connect.linux.do/dash/email")
        )
        assertEquals(
            LinkRoute.Web("https://ldcstore.com/product/12340"),
            resolveLinkRoute("https://ldcstore.com/product/12340")
        )
    }

    @Test
    fun `锚点与空串走 WEB`() {
        assertEquals(LinkRoute.Web("#p-7740154-h-1"), resolveLinkRoute("#p-7740154-h-1"))
        assertEquals(LinkRoute.Web(""), resolveLinkRoute(""))
    }
}
