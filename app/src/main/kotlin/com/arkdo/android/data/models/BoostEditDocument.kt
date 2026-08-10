package com.arkdo.android.data.models

// ==========================================================================
// 与 HarmonyOS 版 models/BoostEditDocument.ets 逐字段对齐。
// boost 编辑器文本分词纯逻辑，无网络来源。
// ==========================================================================

enum class BoostTokenType(val value: String) {
    TEXT("text"),
    EMOJI("emoji")
}

data class BoostEditToken(
    val type: BoostTokenType,
    val value: String
)

object BoostEditDocument {

    fun type(): String = "text"

    /** 把 raw 按 :emoji: 分割成 token 数组。isKnownEmoji 判定已知短码。 */
    fun parse(raw: String, isKnownEmoji: (String) -> Boolean): List<BoostEditToken> {
        val tokens = mutableListOf<BoostEditToken>()
        var textStart = 0
        var cursor = 0
        while (cursor < raw.length) {
            if (raw[cursor] != ':') {
                cursor++
                continue
            }
            val closing = raw.indexOf(':', cursor + 1)
            if (closing < 0) break
            val name = raw.substring(cursor + 1, closing)
            if (name.isEmpty() || !isKnownEmoji(name)) {
                cursor = closing
                continue
            }
            appendText(tokens, raw.substring(textStart, cursor))
            tokens.add(BoostEditToken(BoostTokenType.EMOJI, name))
            cursor = closing + 1
            textStart = cursor
        }
        appendText(tokens, raw.substring(textStart))
        return tokens
    }

    /** 拼接回原文。 */
    fun serialize(tokens: List<BoostEditToken>): String {
        val sb = StringBuilder()
        for (token in tokens) {
            sb.append(if (token.type == BoostTokenType.EMOJI) ":${token.value}:" else token.value)
        }
        return sb.toString()
    }

    /** emoji 记 1，文本按 code point 计数（UTF-16 代理对算 1 个用户字符）。 */
    fun logicalLength(tokens: List<BoostEditToken>): Int {
        var count = 0
        for (token in tokens) {
            count += if (token.type == BoostTokenType.EMOJI) 1 else codePointLength(token.value)
        }
        return count
    }

    /** 去掉首尾 TEXT token 的空白再算长度。 */
    fun logicalLengthTrimmed(tokens: List<BoostEditToken>): Int {
        if (tokens.isEmpty()) return 0
        val copy = tokens.map { BoostEditToken(it.type, it.value) }.toMutableList()
        while (copy.isNotEmpty() && copy[0].type == BoostTokenType.TEXT) {
            copy[0] = BoostEditToken(BoostTokenType.TEXT, copy[0].value.trimStart())
            if (copy[0].value.isNotEmpty()) break
            copy.removeAt(0)
        }
        while (copy.isNotEmpty() && copy[copy.size - 1].type == BoostTokenType.TEXT) {
            val last = copy.size - 1
            copy[last] = BoostEditToken(BoostTokenType.TEXT, copy[last].value.trimEnd())
            if (copy[last].value.isNotEmpty()) break
            copy.removeAt(last)
        }
        return logicalLength(copy)
    }

    fun logicalLengthOfRaw(raw: String, isKnownEmoji: (String) -> Boolean): Int =
        logicalLength(parse(raw.trim(), isKnownEmoji))

    /** 末尾没有空格时，在 emoji 前补空格。 */
    fun needsSpaceBeforeEmoji(tokens: List<BoostEditToken>): Boolean {
        val raw = serialize(tokens)
        return raw.isNotEmpty() && raw.trimEnd().length == raw.length
    }

    private fun appendText(tokens: MutableList<BoostEditToken>, value: String) {
        if (value.isEmpty()) return
        val lastIndex = tokens.size - 1
        if (lastIndex >= 0 && tokens[lastIndex].type == BoostTokenType.TEXT) {
            tokens[lastIndex] = BoostEditToken(
                BoostTokenType.TEXT,
                tokens[lastIndex].value + value
            )
            return
        }
        tokens.add(BoostEditToken(BoostTokenType.TEXT, value))
    }

    /** 按 code point 计数，代理对算 1 个用户字符。 */
    private fun codePointLength(value: String): Int =
        value.codePointCount(0, value.length)
}
