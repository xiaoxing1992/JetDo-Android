package com.jetdo.android.core.designsystem

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.json.JSONArray
import timber.log.Timber

/**
 * Font Awesome 图标,对齐鸿蒙版 views/components/FaIcon.ets。
 *
 * 图标资源在 core:designsystem 的 assets/fa_icons/ 下(423 个单色 SVG,与鸿蒙侧同一份),
 * 经 Coil 的 SvgDecoder 解码后用 ColorFilter 整体染色 —— SVG 内的 path 没有 fill 属性,
 * 默认黑色,整图染色的结果就是纯色图标。
 *
 * 之前 Android 侧走的是 TagIconRegistry.mapIconIdToMaterialIcon():48 条手写的
 * FA → Material Icons 近似映射,表外一律不显示,且多个 FA 图标会撞到同一个 Material 图标。
 * 有了这里就不需要那张表了。
 */
object FaIconSet {

    private const val ASSET_DIR = "fa_icons"
    private const val SYMBOLS_JSON = "linuxdo_fa_symbols.json"

    @Volatile
    private var ids: Set<String>? = null

    /** 可用图标 id 集合,懒加载一次。 */
    fun ids(context: Context): Set<String> {
        ids?.let { return it }
        synchronized(this) {
            ids?.let { return it }
            val loaded = runCatching {
                val raw = context.assets.open(SYMBOLS_JSON).bufferedReader().use { it.readText() }
                val array = JSONArray(raw)
                buildSet {
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }.getOrElse {
                Timber.e(it, "[JetDo][FaIcon] 符号表加载失败")
                emptySet()
            }
            ids = loaded
            Timber.i("[JetDo][FaIcon] 符号表已加载 count=%d", loaded.size)
            return loaded
        }
    }

    /** 该 id 是否有对应 SVG。 */
    fun has(context: Context, iconId: String): Boolean =
        iconId.isNotEmpty() && iconId in ids(context)

    /** id → assets URI;调用方需先用 [has] 判存在。 */
    fun uri(iconId: String): String = "file:///android_asset/$ASSET_DIR/$iconId.svg"
}

/**
 * 渲染一个 FA 图标。[iconId] 不存在时不渲染任何东西(与鸿蒙 FaIconView 的行为一致)。
 */
@Composable
fun FaIcon(
    iconId: String,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    if (!FaIconSet.has(context, iconId)) return

    AsyncImage(
        model = FaIconSet.uri(iconId),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(color),
        contentScale = ContentScale.Fit
    )
}

/** 字号版本,沿用字体缩放;布局需要固定几何时用上面的 [Dp] 版。 */
@Composable
fun FaIcon(
    iconId: String,
    color: Color,
    size: TextUnit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    if (!FaIconSet.has(context, iconId)) return
    val sizeDp = with(LocalDensity.current) { size.toDp() }
    AsyncImage(
        model = FaIconSet.uri(iconId),
        contentDescription = null,
        modifier = modifier.size(sizeDp),
        colorFilter = ColorFilter.tint(color),
        contentScale = ContentScale.Fit
    )
}

/**
 * 渲染一个已烘死颜色的 FA 图标(bookmark-gray / far-square-check-green 这类),不染色。
 */
@Composable
fun FaIconRaw(iconFileName: String, size: Dp) {
    AsyncImage(
        model = "file:///android_asset/fa_icons/$iconFileName",
        contentDescription = null,
        modifier = Modifier.size(size),
        contentScale = ContentScale.Fit
    )
}

/** 由 [modifier] 完全控制几何的版本(如 Layout 内嵌需要精确尺寸时)。 */
@Composable
fun FaIconRaw(iconFileName: String, modifier: Modifier) {
    AsyncImage(
        model = "file:///android_asset/fa_icons/$iconFileName",
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}