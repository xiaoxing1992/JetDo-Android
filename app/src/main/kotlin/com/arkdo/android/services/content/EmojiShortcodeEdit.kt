package com.arkdo.android.services.content

// ==========================================================================
// 短码级别的删除编辑，对齐鸿蒙 utils/EmojiShortcodeEdit.ets。
//
// 场景：输入框里已经插入了 `:smile:`，用户按退格时希望**整段一起删掉**，
// 而不是先删掉收尾的 `:` 留下 `:smile` 这种半截短码（半截短码既不会被渲染成图，
// 也不会被服务端识别，看着像乱码）。
//
// 鸿蒙的 TextArea 有 `onWillDeleteText` 钩子，可以在删除发生前改写；
// Compose 的 BasicTextField 没有等价钩子，所以这里改成**比对删除前后的文本**：
// 从 diff 反推"用户删了哪个字符"，若恰好是已知短码的边界冒号，就把整段一并删掉。
// 好处是纯函数、无平台依赖、可单测。
//
// 代理对（用户直接输入的 emoji 字符如 😀）不在这里处理：Compose 的文本域自己按
// 码点删，不需要干预（鸿蒙那边要手写是因为 TextArea 给的是 UTF-16 下标）。
// ==========================================================================

/** 删除后的文本与光标位置。 */
data class EmojiDeleteResult(val text: String, val caret: Int)

/**
 * 若这次编辑正好删掉了某个**已知短码**的边界冒号，则返回把整段短码一并删掉的结果；
 * 返回 null 表示不需要干预（按用户原本的输入接受）。
 *
 * 只处理"删掉单个 `:`"这一种形态：
 * - 删的是收尾冒号（退格）→ 连同前面的 `:name` 一起删；
 * - 删的是开头冒号（删除键）→ 连同后面的 `name:` 一起删。
 *
 * 一次删多个字符（选中删除、整段替换）不干预——那时用户的意图已经足够明确。
 */
fun normalizeEmojiDeletion(
    old: String,
    new: String,
    isKnownEmoji: (String) -> Boolean
): EmojiDeleteResult? {
    if (new.length >= old.length) return null

    // 前后缀比对求出被删区间 [start, end)。
    val start = old.commonPrefixWith(new).length
    val suffix = old.reversed().commonPrefixWith(new.reversed()).length
    val end = old.length - suffix
    if (end - start != 1) return null
    if (old[start] != ':') return null

    // 收尾冒号：`:name:` 的最后一个 ':'（退格删掉了它）。
    val opening = old.lastIndexOf(':', start - 1)
    if (opening >= 0) {
        val name = old.substring(opening + 1, start)
        if (name.isNotEmpty() && isKnownEmoji(name)) {
            return EmojiDeleteResult(old.removeRange(opening, end), opening)
        }
    }

    // 开头冒号：`:name:` 的第一个 ':'（删除键删掉了它）。
    val closing = old.indexOf(':', start + 1)
    if (closing > start) {
        val name = old.substring(start + 1, closing)
        if (name.isNotEmpty() && isKnownEmoji(name)) {
            return EmojiDeleteResult(old.removeRange(start, closing + 1), start)
        }
    }

    return null
}
