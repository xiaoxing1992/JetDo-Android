package com.jetdo.android.services.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 出示端「分享 key 还活着吗」的判据。
 *
 * 这是扫码登录闭环里最容易写错的一环：Discourse 的 UserSerializer 有三种「没有 key」的
 * 表达方式，语义**完全相反**，压成同一个 false 就会「弹窗 3 秒假报已完成」。
 */
class QrLoginFlowAliveProbeTest {

    private val appName = UserApiKeyLoginManager.QR_APPLICATION_NAME

    @Test
    fun `share key still present is alive`() {
        val body = """{"user":{"id":1,"user_api_keys":[
            {"id":9,"application_name":"$appName","scopes":["一次性密码"]}
        ]}}"""
        assertEquals(true, QrLoginFlow.evaluateShareKeyAlive(body))
    }

    @Test
    fun `null key list means consumed`() {
        // 服务端 `keys.length > 0 ? keys : nil` —— 没有存活 key 时给 null，是明确的「已被消费」。
        assertEquals(false, QrLoginFlow.evaluateShareKeyAlive("""{"user":{"id":1,"user_api_keys":null}}"""))
    }

    @Test
    fun `empty key list means consumed`() {
        // 兼容返回空数组的版本，语义同上。
        assertEquals(false, QrLoginFlow.evaluateShareKeyAlive("""{"user":{"id":1,"user_api_keys":[]}}"""))
    }

    @Test
    fun `only unrelated keys means consumed`() {
        val body = """{"user":{"user_api_keys":[{"id":2,"application_name":"Some other client"}]}}"""
        assertEquals(false, QrLoginFlow.evaluateShareKeyAlive(body))
    }

    @Test
    fun `field not rendered is unknown not consumed`() {
        // 核心回归：`:user_api_keys` 在 private_attributes 里，非本人视角根本不渲染该字段。
        // 这表示「拿不到信息」，绝不是「已被消费」—— 旧实现 `?: return false` 就在这里假报完成。
        assertNull(QrLoginFlow.evaluateShareKeyAlive("""{"user":{"id":1,"username":"neo"}}"""))
    }

    @Test
    fun `malformed body is unknown`() {
        assertNull(QrLoginFlow.evaluateShareKeyAlive("not json at all"))
        assertNull(QrLoginFlow.evaluateShareKeyAlive("{}"))
        assertNull(QrLoginFlow.evaluateShareKeyAlive("""{"user":null}"""))
        assertNull(QrLoginFlow.evaluateShareKeyAlive("""{"user":{"user_api_keys":"oops"}}"""))
    }
}
