package com.arkdo.android.services.content

import com.arkdo.android.data.models.BoostEditDocument
import com.arkdo.android.data.models.BoostTokenType

// ==========================================================================
// 输入框「表情图文混排」的纯逻辑（无 Compose 依赖，可单测）。
//
// 为什么需要它：鸿蒙的 RichEditor 有 addImageSpan，能把 `:smile:` 直接画成图片；
// Compose 的 BasicTextField 做不到 —— 2026-09 逐版核对了 androidx 源码：
//   · BasicTextField 的每一个公开重载（String / TextFieldValue / TextFieldState）
//     都没有 inlineContent 参数，内部 CoreTextField 同样没有；
//   · 新的 state 版配套的 TextFieldBuffer 也没有 appendInlineContent；
//   · 也就是说「可编辑文本里放可组合内容（图片）」是 Compose 官方未支持的能力，
//     只有只读的 Text / BasicText 有 inlineContent。
// （InlineTextContent + 覆盖绘制的常见民间写法，短码 7 个字符宽、图片 1 个字符宽，
//   后面的字全被推歪 —— 那正是"看起来替换了、实际留了 6 个字符空洞"的原因。）
//
// 所以这里换一条路：显示层把每个**已知**短码换成一个全角空格 U+3000。
//   · 它是空白字符，本身不可见 —— 不需要任何 span 样式去把它藏起来；
//   · 它的字宽是 1em（16sp 字号下 = 16dp），与表情图片尺寸相当，于是文本流按
//     「插了一张 1em 宽的图」紧凑排版，不留空洞；图片由 UI 层量出这个占位字符的
//     盒子后覆盖绘制（见 ui/components/EmojiInputText.kt）；
//   · 万一变换失效，最坏结果只是「多一个空格、少一张图」，不会出现方块字或图文叠字。
//
// 输入框的**真值始终是短码原文**：发帖、草稿、字数统计、服务端渲染统统不变，
// 只有「显示」是图文。这与鸿蒙 richEmojiMode 一致（那边内部文本也是 `:name:`，
// 只有编辑器里显示成图片）。
// ==========================================================================

/** 占位字符：全角空格（1em 宽、不可见）。 */
const val EMOJI_INPUT_PLACEHOLDER: Char = '\u3000'

/** 一个表情在显示文本里的落点。 */
data class EmojiInputSlot(
    val name: String,
    val url: String,
    /** 占位字符在 [EmojiInputRender.display] 里的下标。 */
    val displayIndex: Int
)

/**
 * 一次渲染结果：显示文本 + 表情落点 + 双向偏移映射。
 *
 * 两个映射只要求**单调不减**（OffsetMapping 的契约就是这一条）。短码整体只占 1 个显示位，
 * 所以短码内部的偏移吸附到图片边界：前半 → 图前，后半 → 图后。这样
 * ① 在短码末尾输入（插表情后的正常态）光标落在图片右侧；
 * ② 用方向键在短码内部移动时光标在图片两侧跳，不会停在图片中间。
 */
class EmojiInputRender(
    val display: String,
    val slots: List<EmojiInputSlot>,
    private val modelToDisplay: IntArray,
    private val displayToModel: IntArray
) {
    val modelLength: Int get() = modelToDisplay.size - 1
    val displayLength: Int get() = display.length

    /** 原文偏移 → 显示偏移。 */
    fun originalToTransformed(offset: Int): Int =
        modelToDisplay[offset.coerceIn(0, modelLength)]

    /** 显示偏移 → 原文偏移。 */
    fun transformedToOriginal(offset: Int): Int =
        displayToModel[offset.coerceIn(0, displayLength)]
}

/**
 * 把短码原文渲染成「图文可显示的文本」。
 *
 * @param resolveUrl 短名 → 图片 url；返回 null 表示不是本站在录的表情，该短码按普通文本
 *   原样保留（不替换、不画图）。把认不出来的 `:xxx:` 变成空白或空档都比留着原文更糟。
 */
fun renderEmojiInput(model: String, resolveUrl: (String) -> String?): EmojiInputRender {
    // 复用 boost 编辑器同一份分词语义，避免第二套扫描逻辑日后走岔。
    val tokens = BoostEditDocument.parse(model) { resolveUrl(it) != null }

    val display = StringBuilder(model.length)
    val slots = mutableListOf<EmojiInputSlot>()
    // modelToDisplay[i] = 原文偏移 i 对应的显示偏移；比原文长一位（末尾光标）。
    val modelToDisplay = IntArray(model.length + 1)
    // displayToModel[i] = 显示偏移 i 对应的原文偏移；比显示文本长一位（末尾光标）。
    val displayToModel = ArrayList<Int>(model.length + 1)

    var modelIndex = 0
    for (token in tokens) {
        if (token.type != BoostTokenType.EMOJI) {
            // 普通文本：逐字符 1:1，先记「该字符之前的光标位置」再写入。
            for (contained in token.value) {
                modelToDisplay[modelIndex] = display.length
                displayToModel.add(modelIndex)
                display.append(contained)
                modelIndex++
            }
            continue
        }

        val name = token.value
        val shortcodeLength = name.length + 2 // 两侧冒号
        val displayIndex = display.length
        slots.add(EmojiInputSlot(name, resolveUrl(name).orEmpty(), displayIndex))
        // 光标在短码之前 = 图片之前；短码内部及之后一律吸附到图片之后。
        modelToDisplay[modelIndex] = displayIndex
        for (offset in 1..shortcodeLength) {
            modelToDisplay[modelIndex + offset] = displayIndex + 1
        }
        displayToModel.add(modelIndex)
        display.append(EMOJI_INPUT_PLACEHOLDER)
        modelIndex += shortcodeLength
    }

    // 末尾光标：原文末尾 → 显示末尾。
    modelToDisplay[model.length] = display.length
    displayToModel.add(model.length)

    return EmojiInputRender(display.toString(), slots, modelToDisplay, displayToModel.toIntArray())
}
