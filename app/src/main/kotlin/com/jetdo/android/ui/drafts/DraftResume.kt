package com.jetdo.android.ui.drafts

import java.io.Serializable

/**
 * 从草稿列表打开帖子详情时带过去的续写内容。
 * 不放进路由 query：正文可能很长，和发帖续写一样走 savedStateHandle。
 */
data class DraftResume(
    val draftKey: String,
    val sequence: Long,
    val reply: String,
    val editPostId: Long? = null,
    val originalText: String? = null
) : Serializable
