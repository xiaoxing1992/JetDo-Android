package com.arkdo.android.ui.components

import com.arkdo.android.data.models.User
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 头像地址补全与候选链。
 *
 * 对齐鸿蒙 UserAvatar.ets 的 resolveAvatarUrl() / avatarCandidates()。
 * 这条测试存在的理由：linux.do 的动图头像 animated_avatar 是 //cdn.ldstatic.com/...
 * 形态的协议相对地址，之前的补全逻辑只认 "http" 开头，// 开头被拼成
 * https://linux.do//cdn.ldstatic.com/...，必然 404 —— 动图头像永远显示不出来，
 * 表现成首字母占位，且没有任何报错。鸿蒙侧专门处理了这个形态。
 */
class UserAvatarUrlTest {

    private val user = User(
        id = 1,
        username = "neo",
        avatarTemplate = "/user_avatar/linux.do/neo/{size}/1_2.png",
        animatedAvatar = "//cdn.ldstatic.com/original/4X/a/b/c.gif"
    )

    @Test
    fun `协议相对地址补全为 https`() {
        assertEquals(
            "https://cdn.ldstatic.com/original/4X/a/b/c.gif",
            resolveAvatarUrl("//cdn.ldstatic.com/original/4X/a/b/c.gif")
        )
    }

    @Test
    fun `站内绝对路径拼接 BASE_URL`() {
        assertEquals(
            "https://linux.do/user_avatar/linux.do/neo/96/1_2.png",
            resolveAvatarUrl("/user_avatar/linux.do/neo/{size}/1_2.png".replace("{size}", "96"))
        )
    }

    @Test
    fun `无斜杠相对路径补斜杠拼接`() {
        assertEquals("https://linux.do/avatar.png", resolveAvatarUrl("avatar.png"))
    }

    @Test
    fun `绝对地址原样返回`() {
        assertEquals("https://a.example.com/x.png", resolveAvatarUrl("https://a.example.com/x.png"))
        assertEquals("http://a.example.com/x.png", resolveAvatarUrl("http://a.example.com/x.png"))
    }

    @Test
    fun `动图候选排在静态候选之前`() {
        val candidates = avatarCandidates(user, "96")
        assertEquals(
            listOf(
                "https://cdn.ldstatic.com/original/4X/a/b/c.gif",
                "https://linux.do/user_avatar/linux.do/neo/96/1_2.png"
            ),
            candidates
        )
    }

    @Test
    fun `size 占位符在静态候选上替换`() {
        val staticOnly = user.copy(animatedAvatar = null)
        assertEquals(
            listOf("https://linux.do/user_avatar/linux.do/neo/96/1_2.png"),
            avatarCandidates(staticOnly, "96")
        )
    }

    @Test
    fun `空串与缺失字段不产生候选`() {
        assertEquals(emptyList<String>(), avatarCandidates(null, "96"))
        assertEquals(
            emptyList<String>(),
            avatarCandidates(User(id = 1, username = "neo"), "96")
        )
        assertEquals(
            emptyList<String>(),
            avatarCandidates(user.copy(animatedAvatar = "", avatarTemplate = ""), "96")
        )
    }

    @Test
    fun `只有动图时不动到静态候选`() {
        val animatedOnly = user.copy(avatarTemplate = null)
        assertEquals(
            listOf("https://cdn.ldstatic.com/original/4X/a/b/c.gif"),
            avatarCandidates(animatedOnly, "96")
        )
    }
}
