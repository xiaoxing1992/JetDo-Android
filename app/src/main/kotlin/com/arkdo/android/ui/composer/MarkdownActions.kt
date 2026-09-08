package com.arkdo.android.ui.composer

/**
 * Markdown 工具栏插入逻辑,逐函数对齐鸿蒙 utils/markdownInsert.ets。
 *
 * 纯函数:输入全文 + 选区,输出应用某格式后的新文本与新选区,不碰任何 UI 状态,便于单测。
 * 选区相等表示无选区(即光标位置)。
 */
object MarkdownActions {

    /** 编辑器文本状态。 */
    data class EditState(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    /**
     * 工具栏动作描述,对齐鸿蒙 markdownInsert.ets 的 MdActionKind。
     * - Wrap:有选区包住选中;无选区插 before+placeholder+after 并选中占位。
     * - LinePrefix:给选区覆盖的每行行首加前缀(ordered=true 递增序号)。
     * - Insert:光标处插入 snippet,光标落到末尾(图片 markdown)。
     * - InsertCursor:插入 snippet 并把光标定位到片内偏移(围栏代码块)。
     */
    sealed class MdAction {
        class Wrap(val before: String, val after: String, val placeholder: String) : MdAction()
        class LinePrefix(val prefix: String, val ordered: Boolean = false) : MdAction()
        class Insert(val snippet: String) : MdAction()
        class InsertCursor(val snippet: String, val cursorOffset: Int, val selectLength: Int = 0) : MdAction()
    }

    fun applyAction(state: EditState, action: MdAction): EditState = when (action) {
        is MdAction.Wrap -> wrapSelection(state, action.before, action.after, action.placeholder)
        is MdAction.LinePrefix -> applyLinePrefix(state, action.prefix, action.ordered)
        is MdAction.Insert -> insertText(state, action.snippet)
        is MdAction.InsertCursor ->
            insertWithCursor(state, action.snippet, action.cursorOffset, action.selectLength)
    }

    /** 规整选区:钳到 [0,len] 且 start<=end,避免上游给的越界/倒置值算错。 */
    private fun normalize(state: EditState): EditState {
        val len = state.text.length
        var start = state.selectionStart
        var end = state.selectionEnd
        if (start < 0) start = 0
        if (end < 0) end = 0
        if (start > len) start = len
        if (end > len) end = len
        if (start > end) {
            val tmp = start; start = end; end = tmp
        }
        return EditState(state.text, start, end)
    }

    /** 包裹型(加粗/斜体/行内代码/链接):有选区则包住选中文本并保持选中;无选区插 before+placeholder+after 并选中占位。 */
    fun wrapSelection(input: EditState, before: String, after: String, placeholder: String): EditState {
        val state = normalize(input)
        val start = state.selectionStart
        val end = state.selectionEnd
        val head = state.text.substring(0, start)
        val tail = state.text.substring(end)

        if (start == end) {
            val text = head + before + placeholder + after + tail
            val selStart = start + before.length
            return EditState(text, selStart, selStart + placeholder.length)
        }
        val selected = state.text.substring(start, end)
        val text = head + before + selected + after + tail
        return EditState(text, start + before.length, end + before.length)
    }

    /**
     * 行首前缀型(引用/列表/标题):对选区覆盖到的每一行行首加 prefix。ordered=true 时前缀带递增序号。
     * 无选区时作用于光标所在行。加完后选中被影响的整段,方便继续操作。
     */
    fun applyLinePrefix(input: EditState, prefix: String, ordered: Boolean): EditState {
        val state = normalize(input)
        val text = state.text
        val hadSelection = state.selectionEnd > state.selectionStart
        val lineStart = text.lastIndexOf('\n', state.selectionStart - 1) + 1
        val searchFrom = if (hadSelection) state.selectionEnd - 1 else state.selectionStart
        val nextNl = text.indexOf('\n', searchFrom)
        val lineEnd = if (nextNl < 0) text.length else nextNl

        val head = text.substring(0, lineStart)
        val block = text.substring(lineStart, lineEnd)
        val tail = text.substring(lineEnd)
        val lines = block.split('\n')
        val prefixed = lines.mapIndexed { index, line ->
            if (ordered) "${index + 1}. $line" else prefix + line
        }
        val newBlock = prefixed.joinToString("\n")
        val newText = head + newBlock + tail
        // 无选区(仅光标):光标落到当前行前缀之后,便于直接接着打字。否则整行(含「# 」前缀)被选中,
        // 用户一打字就替换掉前缀 —— 「点了 # 打字后 # 消失、标题失效」的真凶。有选区时保持整块选中。
        return if (!hadSelection) {
            val firstPrefixLen = if (ordered) "1. ".length else prefix.length
            val caret = lineStart + firstPrefixLen
            EditState(newText, caret, caret)
        } else {
            EditState(newText, lineStart, lineStart + newBlock.length)
        }
    }

    /** 在光标/选区处插入一段文本(替换选中),光标落到插入内容末尾。用于图片 markdown 等直插场景。 */
    fun insertText(input: EditState, snippet: String): EditState {
        val state = normalize(input)
        val head = state.text.substring(0, state.selectionStart)
        val tail = state.text.substring(state.selectionEnd)
        val text = head + snippet + tail
        val caret = state.selectionStart + snippet.length
        return EditState(text, caret, caret)
    }

    /** 插入片段并把光标定位到片段内某偏移(如链接 [](url) 把光标放进 () 里)。selectLength>0 时选中占位文字。 */
    fun insertWithCursor(input: EditState, snippet: String, cursorOffset: Int, selectLength: Int): EditState {
        val state = normalize(input)
        val head = state.text.substring(0, state.selectionStart)
        val tail = state.text.substring(state.selectionEnd)
        val text = head + snippet + tail
        val base = state.selectionStart + cursorOffset
        return EditState(text, base, base + selectLength.coerceAtLeast(0))
    }
}
