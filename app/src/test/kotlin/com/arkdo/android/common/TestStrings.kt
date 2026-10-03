package com.arkdo.android.common

import com.arkdo.android.R
import com.arkdo.android.core.data.AppContextHolder
import org.junit.Before
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JVM 单测的字符串解析器：把 app 模块 values/strings.xml 读进来，
 * 装到 AppContextHolder.resolver 上，让服务层单测能拿到真实中文文案。
 * 需要 AppContextHolder 文案的单测类加一行 @Before install() 即可。
 */
object TestStrings {

    private val templates: Map<String, String> by lazy {
        val f = File("src/main/res/values/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("string")
        val map = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes.getNamedItem("name").nodeValue
            map[name] = node.textContent
        }
        map
    }

    private val nameById: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    fun install() {
        AppContextHolder.resolver = { resId, args ->
            val name = nameById[resId]
                ?: error("R.string 常量不在 app 模块: #$resId")
            val tpl = templates[name]
                ?: error("strings.xml 无条目: $name")
            format(tpl, args)
        }
    }

    /** 处理 %1$s / %2$d / %% 等位置参数格式。 */
    private fun format(tpl: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return tpl.replace("%%", "%")
        var out = tpl
        args.forEachIndexed { idx, arg ->
            out = out.replace("%${idx + 1}\$s", arg.toString())
            out = out.replace("%${idx + 1}\$d", arg.toString())
        }
        return out.replace("%%", "%")
    }
}
