package com.arkdo.android.services.connect

import com.arkdo.android.data.models.ConnectBarItem
import com.arkdo.android.data.models.ConnectLevelData
import com.arkdo.android.data.models.ConnectQuotaItem
import com.arkdo.android.data.models.ConnectRingItem
import com.arkdo.android.data.models.ConnectVetoItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ==========================================================================
// 与 HarmonyOS 版 services/connect/ConnectLevelParser.ets 对齐。
// 注入 connect.linux.do 文档抽取「升级要求」卡片;页面未就绪(SSO/挑战页)返回空串。
// 脚本是浏览器 JS,不是 Kotlin。
// ==========================================================================

const val CONNECT_EXTRACT_SCRIPT: String = """(function(){
  try {
    var card = document.querySelector('.page-content .card') || document.querySelector('.card');
    if (!card || !card.querySelector('.tl3-rings')) { return ''; }
    var txt = function(el, sel){ if(!el) return ''; var n = el.querySelector(sel); return n ? (n.textContent || '').trim() : ''; };
    var numFrom = function(style, key){ if(!style) return 0; var m = style.match(new RegExp('--' + key + ':\\s*([0-9.]+)')); return m ? parseFloat(m[1]) : 0; };
    var badgeEl = card.querySelector('.card-header .badge');
    var rings = Array.from(card.querySelectorAll('.tl3-ring')).map(function(r){
      var c = r.querySelector('.tl3-ring-circle');
      var style = c ? (c.getAttribute('style') || '') : '';
      return {
        label: txt(r, '.tl3-ring-label'),
        current: txt(r, '.tl3-ring-current'),
        target: txt(r, '.tl3-ring-target'),
        val: numFrom(style, 'val'),
        max: numFrom(style, 'max'),
        met: !!(c && c.classList.contains('met'))
      };
    });
    var bars = Array.from(card.querySelectorAll('.tl3-bar-item')).map(function(b){
      var nums = b.querySelector('.tl3-bar-nums');
      var fill = b.querySelector('.tl3-bar-fill');
      var style = fill ? (fill.getAttribute('style') || '') : '';
      return {
        label: txt(b, '.tl3-bar-label'),
        nums: nums ? (nums.textContent || '').trim() : '',
        val: numFrom(style, 'val'),
        max: numFrom(style, 'max'),
        met: !!(nums && nums.classList.contains('met'))
      };
    });
    var quotas = Array.from(card.querySelectorAll('.tl3-quota-card')).map(function(qc){
      return {
        label: txt(qc, '.tl3-quota-label'),
        nums: txt(qc, '.tl3-quota-nums'),
        slots: qc.querySelectorAll('.tl3-slot').length,
        used: qc.querySelectorAll('.tl3-slot.used').length,
        met: qc.classList.contains('met')
      };
    });
    var vetos = Array.from(card.querySelectorAll('.tl3-veto-item')).map(function(v){
      var met = v.classList.contains('met');
      var face = v.querySelector(met ? '.tl3-veto-front' : '.tl3-veto-back') || v;
      return {
        label: txt(face, '.tl3-veto-label'),
        desc: txt(face, '.tl3-veto-desc'),
        value: txt(face, '.tl3-veto-value'),
        met: met
      };
    });
    var conc = card.querySelector('.status-unmet, .status-met');
    return JSON.stringify({
      title: txt(card, '.card-title'),
      statusLabel: badgeEl ? (badgeEl.textContent || '').trim() : '',
      statusIsMet: !!(badgeEl && badgeEl.classList.contains('badge-success')),
      subtitle: txt(card, '.card-subtitle'),
      rings: rings,
      bars: bars,
      quotas: quotas,
      vetos: vetos,
      hint: txt(card, '.text-hint'),
      conclusion: conc ? (conc.textContent || '').trim() : '',
      conclusionIsMet: !!(conc && conc.classList.contains('status-met'))
    });
  } catch (e) { return ''; }
})();"""

object ConnectLevelParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析 evaluateJavascript 的返回值。null 表示页面未就绪或格式异常,调用方据此重试。
     * WebView 对字符串返回值会再套一层 JSON 编码,故以引号起止时先解包一次。
     */
    fun parse(raw: String?): ConnectLevelData? {
        if (raw.isNullOrBlank()) return null
        var text = raw.trim()
        if (text.isEmpty() || text == "null" || text == "undefined" || text == "\"\"") return null
        if (text.startsWith("\"") && text.endsWith("\"")) {
            text = runCatching { json.decodeFromString<String>(text) }.getOrNull() ?: return null
        }
        text = text.trim()
        if (!text.startsWith("{")) return null
        val obj = runCatching { json.decodeFromString<RawConnectLevel>(text) }.getOrNull() ?: return null
        if (obj.rings == null) return null
        return normalize(obj)
    }

    private fun normalize(obj: RawConnectLevel): ConnectLevelData = ConnectLevelData(
        title = obj.title?.takeIf { it.isNotEmpty() } ?: "信任级别要求",
        statusLabel = obj.statusLabel.orEmpty(),
        statusIsMet = obj.statusIsMet == true,
        subtitle = obj.subtitle.orEmpty(),
        rings = obj.rings.orEmpty().map { ring ->
            ConnectRingItem(
                label = ring.label.orEmpty(),
                current = ring.current.orEmpty(),
                target = ring.target.orEmpty(),
                val_ = ring.value ?: 0.0,
                max = ring.max ?: 0.0,
                met = ring.met == true
            )
        },
        bars = obj.bars.orEmpty().map { bar ->
            ConnectBarItem(
                label = bar.label.orEmpty(),
                nums = bar.nums.orEmpty(),
                val_ = bar.value ?: 0.0,
                max = bar.max ?: 0.0,
                met = bar.met == true
            )
        },
        quotas = obj.quotas.orEmpty().map { quota ->
            ConnectQuotaItem(
                label = quota.label.orEmpty(),
                nums = quota.nums.orEmpty(),
                slots = quota.slots ?: 0,
                used = quota.used ?: 0,
                met = quota.met == true
            )
        },
        vetos = obj.vetos.orEmpty().map { veto ->
            ConnectVetoItem(
                label = veto.label.orEmpty(),
                desc = veto.desc.orEmpty(),
                value = veto.value.orEmpty(),
                met = veto.met == true
            )
        },
        hint = obj.hint.orEmpty(),
        conclusion = obj.conclusion.orEmpty(),
        conclusionIsMet = obj.conclusionIsMet == true
    )
}

@Serializable
private data class RawConnectLevel(
    val title: String? = null,
    val statusLabel: String? = null,
    val statusIsMet: Boolean? = null,
    val subtitle: String? = null,
    val rings: List<RawRing>? = null,
    val bars: List<RawBar>? = null,
    val quotas: List<RawQuota>? = null,
    val vetos: List<RawVeto>? = null,
    val hint: String? = null,
    val conclusion: String? = null,
    val conclusionIsMet: Boolean? = null
)

@Serializable
private data class RawRing(
    val label: String? = null,
    val current: String? = null,
    val target: String? = null,
    @SerialName("val") val value: Double? = null,
    val max: Double? = null,
    val met: Boolean? = null
)

@Serializable
private data class RawBar(
    val label: String? = null,
    val nums: String? = null,
    @SerialName("val") val value: Double? = null,
    val max: Double? = null,
    val met: Boolean? = null
)

@Serializable
private data class RawQuota(
    val label: String? = null,
    val nums: String? = null,
    val slots: Int? = null,
    val used: Int? = null,
    val met: Boolean? = null
)

@Serializable
private data class RawVeto(
    val label: String? = null,
    val desc: String? = null,
    val value: String? = null,
    val met: Boolean? = null
)
