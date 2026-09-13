package com.arkdo.android.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.json.JSONArray
import timber.log.Timber

private const val ASSET_DIR = "fa_icons"
private const val SYMBOLS_JSON = "linuxdo_fa_symbols.json"

object FaIconSet {
    @Volatile
    private var ids: Set<String>? = null

    fun has(context: android.content.Context, iconId: String): Boolean =
        iconId.isNotEmpty() && iconId in ids(context)

    fun ids(context: android.content.Context): Set<String> {
        ids?.let { return it }
        synchronized(this) {
            ids?.let { return it }
            val loaded = runCatching {
                val raw = context.assets.open(SYMBOLS_JSON).bufferedReader().use { it.readText() }
                val array = JSONArray(raw)
                buildSet {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.optString("id")
                            ?.takeIf(String::isNotEmpty)?.let(::add)
                    }
                }
            }.getOrElse {
                Timber.w(it, "[ArkDO][FaIcon] symbol list unavailable")
                emptySet()
            }
            ids = loaded
            return loaded
        }
    }
}

/** Renders the same monochrome Font Awesome SVG assets as the legacy home. */
@Composable
fun FaIcon(iconId: String, color: Color, size: TextUnit = 12.sp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!FaIconSet.has(context, iconId)) return
    val sizeDp = with(LocalDensity.current) { size.toDp() }
    AsyncImage(
        model = "file:///android_asset/$ASSET_DIR/$iconId.svg",
        contentDescription = null,
        modifier = modifier.size(sizeDp),
        colorFilter = ColorFilter.tint(color),
        contentScale = ContentScale.Fit
    )
}

/** Fixed-size overload for layouts whose geometry must not follow font scaling. */
@Composable
fun FaIcon(iconId: String, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!FaIconSet.has(context, iconId)) return
    AsyncImage(
        model = "file:///android_asset/$ASSET_DIR/$iconId.svg",
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(color),
        contentScale = ContentScale.Fit
    )
}

/** Renders a pre-colored SVG used by the legacy title markers. */
@Composable
fun FaIconRaw(iconFileName: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = "file:///android_asset/$ASSET_DIR/$iconFileName",
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}
