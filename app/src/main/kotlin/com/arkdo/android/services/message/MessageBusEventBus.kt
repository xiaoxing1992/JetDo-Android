package com.arkdo.android.services.message

import com.arkdo.android.services.challenge.ChallengeEventBus
import com.arkdo.android.services.network.ChallengeState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber

// ==========================================================================
// 与 HarmonyOS 版 services/message/MessageBusEventBus.ets 对齐。
// MessageBus 长轮询事件总线：解析注入脚本上抛的 payload，广播给订阅方。
// ==========================================================================

/** MessageBus 事件模型。 */
@Serializable
data class MessageBusEvent(
    val type: String? = null,
    val channel: String? = null,
    val messageId: Long? = null,
    val data: String? = null,
    val statusCode: Int? = null,
    val stage: String? = null,
    val message: String? = null
)

typealias MessageBusListener = (MessageBusEvent) -> Unit

object MessageBusEventBus {
    private val listeners = mutableMapOf<Long, MessageBusListener>()
    private var nextListenerId = 0L
    private val json = Json { ignoreUnknownKeys = true }

    fun subscribe(listener: MessageBusListener): () -> Unit {
        val id = nextListenerId++
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    /**
     * 解析并广播一条 payload（来自注入的 MessageBus 长轮询脚本）。
     * type === 'challenge' 时转 ChallengeEventBus.notifyChallengeRequired。
     */
    fun publishPayload(payload: String) {
        val event = runCatching {
            val obj = json.parseToJsonElement(payload).jsonObject
            MessageBusEvent(
                type = obj["type"]?.jsonPrimitive?.contentOrNull,
                channel = obj["channel"]?.jsonPrimitive?.contentOrNull,
                messageId = obj["message_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                data = obj["data"]?.jsonPrimitive?.contentOrNull,
                statusCode = obj["statusCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                stage = obj["stage"]?.jsonPrimitive?.contentOrNull,
                message = obj["message"]?.jsonPrimitive?.contentOrNull
            )
        }.getOrElse {
            Timber.w(it, "[ArkDO][MessageBus] payload parse failed len=%d", payload.length)
            return
        }

        if (event.type == "challenge") {
            ChallengeEventBus.notifyChallengeRequired(
                event.channel ?: "/message-bus",
                event.statusCode ?: 0,
                ChallengeState.REQUIRED
            )
        }

        listeners.values.toList().forEach { l ->
            runCatching { l(event) }.onFailure {
                Timber.e(it, "[ArkDO][MessageBus] listener threw")
            }
        }
    }
}
