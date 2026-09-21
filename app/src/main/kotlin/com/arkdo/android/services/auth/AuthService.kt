package com.arkdo.android.services.auth

import com.arkdo.android.data.models.User
import com.arkdo.android.services.network.ChallengeState
import com.arkdo.android.services.network.JsonNetworkClient
import com.arkdo.android.services.network.NetworkResult
import com.arkdo.android.services.network.NetworkResults
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ==========================================================================
// 与 HarmonyOS 版 services/auth/AuthService.ets 逐字段对齐。
// 当前会话 / /session/current.json 与登出 DELETE /session/{username}。
// ==========================================================================

private const val CURRENT_SESSION_PATH = "/session/current.json"
// 未登录时 /session/current.json 的状态码。Discourse 对匿名请求回 404 + 空 body,
// 而非 200 + current_user:null——它是登出/会话过期的判据,不是错误。
private const val CURRENT_SESSION_ANONYMOUS_STATUS = 404

@Serializable
private data class DiscourseCurrentSessionResponse(
    @SerialName("current_user") val currentUser: DiscourseCurrentUser? = null
)

@Serializable
private data class DiscourseCurrentUser(
    @SerialName("id") val id: Int? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("avatar_template") val avatarTemplate: String? = null,
    @SerialName("animated_avatar") val animatedAvatar: String? = null,
    @SerialName("unread_notifications") val unreadNotifications: Int? = null,
    @SerialName("unread_high_priority_notifications") val unreadHighPriorityNotifications: Int? = null,
    // 总未读(含私信等高优先级)，对应铃铛角标真值(user.rb all_unread_notifications_count:816)。
    @SerialName("all_unread_notifications_count") val allUnreadNotificationsCount: Int? = null,
    @SerialName("draft_count") val draftCount: Int? = null,
    @SerialName("trust_level") val trustLevel: Int? = null,
    @SerialName("admin") val admin: Boolean? = null,
    @SerialName("moderator") val moderator: Boolean? = null
)

class AuthService(private val bridge: JsonNetworkClient) {

    suspend fun getCurrentUser(): NetworkResult<User?> {
        val response = bridge.getJson(CURRENT_SESSION_PATH)
        if (!response.success) {
            // 匿名会话在 Discourse 上是 404 + 空 body(SessionController#current 的 render body: nil,
            // status: 404),不是 200 + current_user:null。这是「确认未登录」的正向信号,必须与
            // 「请求没成功」区分开:上层(SessionChecker/RootTabPage)对失败一律保留上一次登录态,
            // 若把这条 404 当失败上抛,登出后登录态永远清不掉,只能杀进程重启。
            // 同时要求 challengeState=NONE:CF 若用 404 壳子返回挑战页,仍按失败走过盾链路,不误判成登出。
            if (response.statusCode == CURRENT_SESSION_ANONYMOUS_STATUS &&
                response.challengeState == ChallengeState.NONE
            ) {
                return NetworkResult(
                    success = true,
                    statusCode = response.statusCode,
                    challengeState = response.challengeState,
                    data = null
                )
            }
            return NetworkResults.forwardFailure(response)
        }
        if (response.data == null) {
            return unexpectedResponse(response, "Linux.do 当前会话响应为空。")
        }
        return try {
            val payload = Json { ignoreUnknownKeys = true }
                .decodeFromString<DiscourseCurrentSessionResponse>(response.data)
            val sourceUser = payload.currentUser
            if (sourceUser == null) {
                return NetworkResult(
                    success = true,
                    statusCode = response.statusCode,
                    challengeState = response.challengeState,
                    data = null
                )
            }
            if (sourceUser.id == null || sourceUser.username == null) {
                return unexpectedResponse(response, "Linux.do 当前用户响应字段无效。")
            }
            NetworkResult(
                success = true,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                data = User(
                    id = sourceUser.id.toLong(),
                    username = sourceUser.username,
                    name = sourceUser.name,
                    avatarTemplate = sourceUser.avatarTemplate,
                    animatedAvatar = sourceUser.animatedAvatar,
                    unreadNotifications = sourceUser.allUnreadNotificationsCount
                        ?: sourceUser.unreadNotifications ?: 0,
                    unreadHighPriorityNotifications = sourceUser.unreadHighPriorityNotifications ?: 0,
                    draftCount = sourceUser.draftCount ?: 0,
                    trustLevel = sourceUser.trustLevel,
                    admin = sourceUser.admin,
                    moderator = sourceUser.moderator
                )
            )
        } catch (e: Exception) {
            unexpectedResponse(response, "Linux.do 当前会话响应不是有效 JSON。")
        }
    }

    /** 退出登录：DELETE /session/{username}。成功后由调用方触发 SessionEventBus。 */
    suspend fun logout(username: String): NetworkResult<Boolean> {
        val response = bridge.deleteForm("/session/${encodeURIComponent(username)}", "")
        if (!response.success) {
            return NetworkResult(
                success = false,
                statusCode = response.statusCode,
                challengeState = response.challengeState,
                error = response.error
            )
        }
        return NetworkResult(
            success = true,
            statusCode = response.statusCode,
            challengeState = response.challengeState,
            data = true
        )
    }

    private fun unexpectedResponse(response: NetworkResult<String>, message: String): NetworkResult<User?> =
        NetworkResults.unexpectedResponse(response.statusCode, response.challengeState, message)

    private fun encodeURIComponent(value: String): String {
        val builder = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (isUnreserved(c)) builder.append(c) else builder.append('%').append("%02X".format(b.toInt() and 0xFF))
        }
        return builder.toString()
    }

    private fun isUnreserved(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~'
}