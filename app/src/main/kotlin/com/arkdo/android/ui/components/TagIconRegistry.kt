package com.arkdo.android.ui.components

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 标签图标注册表 - 1:1 对齐鸿蒙版 TagIconRegistry.ets
 *
 * 从 linuxdo_tag_icons.json 加载标签的图标和颜色配置
 */
object TagIconRegistry {

    @Serializable
    data class TagIconStyle(
        val tagName: String,
        val iconId: String,
        val color: String
    )

    private val tagIconMap = mutableMapOf<String, TagIconStyle>()
    private var tagIconsLoaded = false

    /**
     * 从应用上下文加载标签图标配置
     */
    fun ensureTagIconsLoaded(context: Context): Int {
        if (tagIconsLoaded && tagIconMap.isNotEmpty()) {
            return tagIconMap.size
        }

        return try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier(
                    "linuxdo_tag_icons",
                    "raw",
                    context.packageName
                )
            )
            val json = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            loadTagIconsFromJson(json)
        } catch (e: Exception) {
            tagIconMap.clear()
            tagIconsLoaded = true
            0
        }
    }

    /**
     * 从 JSON 字符串加载标签图标配置
     */
    fun loadTagIconsFromJson(json: String): Int {
        return try {
            val styles = Json.decodeFromString<List<TagIconStyle>>(json)
            tagIconMap.clear()

            styles.forEach { style ->
                val normalizedKey = normalizeTagKey(style.tagName)
                if (normalizedKey.isNotEmpty() && style.iconId.isNotEmpty()) {
                    tagIconMap[normalizedKey] = style
                }
            }

            tagIconsLoaded = true
            tagIconMap.size
        } catch (e: Exception) {
            tagIconMap.clear()
            tagIconsLoaded = true
            0
        }
    }

    /**
     * 获取标签的图标样式配置
     */
    fun getTagIconStyle(tagName: String): TagIconStyle? {
        return tagIconMap[normalizeTagKey(tagName)]
    }

    /**
     * 解析颜色字符串为 Compose Color
     */
    fun parseColor(colorHex: String): Color? {
        return try {
            val cleanHex = colorHex.removePrefix("#")
            val colorInt = android.graphics.Color.parseColor("#$cleanHex")
            Color(colorInt)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * FontAwesome 图标 ID 到 Material Icons 的映射
     *
     * 注：Material Icons 与 FontAwesome 不完全一致，这里选择视觉相近的图标
     */
    fun mapIconIdToMaterialIcon(iconId: String): ImageVector? {
        return when (iconId) {
            "user-injured" -> Icons.Default.Person
            "gift" -> Icons.Default.CardGiftcard
            "faucet" -> Icons.Default.WaterDrop
            "network-wired" -> Icons.Default.Lan
            "face-smile" -> Icons.Default.SentimentSatisfied
            "venus" -> Icons.Default.Face
            "user-slash" -> Icons.Default.PersonOff
            "coins" -> Icons.Default.MonetizationOn
            "bullhorn" -> Icons.Default.Campaign
            "receipt" -> Icons.Default.Receipt
            "people-group" -> Icons.Default.Groups
            "ethernet" -> Icons.Default.Cable
            "heart-pulse" -> Icons.Default.FitnessCenter
            "hand-holding-dollar" -> Icons.Default.AttachMoney
            "droplet-slash" -> Icons.Default.WaterDrop
            "thumbs-up" -> Icons.Default.ThumbUp
            "circle-question" -> Icons.Default.Help
            "route" -> Icons.Default.Route
            "pepper-hot" -> Icons.Default.LocalDining
            "terminal" -> Icons.Default.Terminal
            "car" -> Icons.Default.DirectionsCar
            "microchip" -> Icons.Default.Memory
            "hands-praying" -> Icons.Default.Favorite
            "brain" -> Icons.Default.Psychology
            "bug" -> Icons.Default.BugReport
            "spider" -> Icons.Default.BugReport
            "file-code" -> Icons.Default.Code
            "comment-dollar" -> Icons.Default.AttachMoney
            "camera" -> Icons.Default.CameraAlt
            "tree" -> Icons.Default.Park
            "database" -> Icons.Default.Storage
            "calculator" -> Icons.Default.Calculate
            "droplet" -> Icons.Default.WaterDrop
            "user-secret" -> Icons.Default.Security
            "radiation" -> Icons.Default.Warning
            "book" -> Icons.Default.MenuBook
            "music" -> Icons.Default.MusicNote
            "video" -> Icons.Default.Videocam
            "blog" -> Icons.Default.Article
            "gamepad" -> Icons.Default.SportsEsports
            "lightbulb" -> Icons.Default.Lightbulb
            "briefcase" -> Icons.Default.Work
            "share" -> Icons.Default.Share
            "palette" -> Icons.Default.Palette
            "arrow-pointer" -> Icons.Default.Mouse
            "headset" -> Icons.Default.Headset
            "triangle-exclamation" -> Icons.Default.Warning
            "server" -> Icons.Default.Dns
            else -> null
        }
    }

    private fun normalizeTagKey(tagName: String): String {
        return tagName.trim().lowercase()
    }
}
