package com.arkdo.android.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest

/**
 * 「动态头像」开关的组合局部（对齐鸿蒙 AnimatedAvatarPrefs + UserAvatar 的 @StorageProp 响应式绑定）。
 *
 * 默认 true（与鸿蒙默认开一致）。app 在导航宿主根部用它提供
 * `AnimatedAvatarPrefs.enabled` 的实时值；本模块（以及 feature 模块）内的头像/flair
 * 渲染点读这个局部取开关，不直接依赖 app 层的 Prefs —— 模块方向问题。
 *
 * 关（false）时的语义：头像与 flair 一律取首帧渲染成静态图，且跳过动图候选
 * （见 [topicAvatarCandidates] 的 includeAnimated 与 [rememberAvatarImageModel]）。
 */
val LocalAnimatedAvatarEnabled = staticCompositionLocalOf { true }

/**
 * 头像 / flair 的加载模型。
 *
 * 开（默认）：直接给 URL，由全局 ImageLoader 的 GIF 解码器自动播放动图。
 * 关：构造一个强制 BitmapFactory 解码的请求 —— BitmapFactory 对 GIF/WebP 只解出
 * 首帧，天然静态。这与鸿蒙「关闭时走 webview 桥取字节、PixelMap 天然只有一帧」
 * 是同一条语义（取首帧），实现按平台各自选路。
 */
@Composable
fun rememberAvatarImageModel(url: String): Any {
    val animated = LocalAnimatedAvatarEnabled.current
    val context = LocalContext.current
    return remember(url, animated) {
        if (animated) {
            url
        } else {
            ImageRequest.Builder(context)
                .data(url)
                .decoderFactory(BitmapFactoryDecoder.Factory())
                .build()
        }
    }
}
