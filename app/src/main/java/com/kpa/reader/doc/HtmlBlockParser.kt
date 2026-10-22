package com.kpa.reader.doc

/**
 * 容错 HTML → 块 解析器。
 *
 * EPUB 的 XHTML 基本是良构的，但 MOBI/KF8 里的 HTML 常年缺闭合标签、属性不加引号，
 * 所以这里不用 XmlPullParser，而是自己扫一遍标签流：块级标签边界断块、行内标签只取文字。
 * 遇到 <div> 也断块 —— 宁可多出几个空块（最后统一过滤），也不能丢内容。
 */
object HtmlBlockParser {

    sealed class Spec {
        data class Text(val text: String, val style: Int) : Spec()
        data class Img(val src: String) : Spec()
    }

    private class Tag(
        val name: String,
        val closing: Boolean,
        val selfClose: Boolean,
        val attrs: String,
        val next: Int
    )

    private val BLOCK_START = setOf(
        "p", "div", "section", "article", "li", "tr", "td", "th",
        "blockquote", "h1", "h2", "h3", "h4", "h5", "h6",
        "pre", "br", "hr", "figure", "figcaption", "dd", "dt", "table"
    )

    private val SKIP_BLOCK = setOf("script", "style", "head", "title")

    private val ENTITY = Regex("&(?:#(\\d{1,7})|#[xX]([0-9a-fA-F]{1,6})|([a-zA-Z]{2,10}));")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "middot" to "·", "bull" to "•", "copy" to "©", "reg" to "®",
        "times" to "×", "divide" to "÷", "deg" to "°"
    )

    fun parse(html: String): List<Spec> {
        val out = ArrayList<Spec>()
        var sb = StringBuilder()
        var style = Block.BODY
        var quoteDepth = 0
        val stack = ArrayList<Int>()

        fun flush() {
            val t = clean(sb.toString())
            if (t.isNotEmpty()) {
                out.add(Spec.Text(t, if (quoteDepth > 0) Block.QUOTE else style))
            }
            sb = StringBuilder()
        }

        val s = html
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '<') {
                // 注释
                if (i + 3 < n && s.startsWith("<!--", i)) {
                    val end = s.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                    continue
                }
                // XML 声明 <?xml ...?>、DOCTYPE、处理指令：整段丢弃，
                // 否则 XHTML 开头这行会被当正文显示出来
                if (i + 1 < n && (s[i + 1] == '?' || s[i + 1] == '!')) {
                    val end = s.indexOf('>', i)
                    i = if (end < 0) n else end + 1
                    continue
                }
                val tag = parseTag(s, i)
                if (tag == null) {
                    // 不是标签（比如单独的 < ），按普通文字处理
                    sb.append(c)
                    i++
                    continue
                }
                i = tag.next
                val name = tag.name

                if (name in SKIP_BLOCK) {
                    if (!tag.closing && !tag.selfClose) {
                        // 跳到对应结束标签
                        val end = findClose(s, i, name)
                        i = end
                    }
                    continue
                }

                if (name == "img" || name == "image") {
                    flush()
                    val src = attr(tag.attrs, "src")
                        ?: attr(tag.attrs, "xlink:href")
                        ?: attr(tag.attrs, "href")
                    if (!src.isNullOrBlank() && !src.startsWith("data:")) {
                        out.add(Spec.Img(src))
                    }
                    continue
                }

                if (name in BLOCK_START) {
                    flush()
                    if (name == "br" || name == "hr") continue
                    if (!tag.closing && !tag.selfClose) {
                        stack.add(style)
                        when (name) {
                            "h1" -> style = Block.H1
                            "h2" -> style = Block.H2
                            "h3", "h4", "h5", "h6" -> style = Block.H3
                            "blockquote" -> quoteDepth++
                        }
                    } else if (tag.closing || tag.selfClose) {
                        when (name) {
                            "h1", "h2", "h3", "h4", "h5", "h6" -> style = stack.removeLastOrNull() ?: Block.BODY
                            "blockquote" -> {
                                if (quoteDepth > 0) quoteDepth--
                                style = stack.removeLastOrNull() ?: Block.BODY
                            }
                        }
                    }
                    continue
                }

                // 行内标签（span/em/strong/a/font…）忽略，只保留文字
                continue
            }

            // 普通文字
            val next = s.indexOf('<', i)
            if (next < 0) {
                sb.append(s, i, n)
                break
            }
            sb.append(s, i, next)
            i = next
        }
        flush()

        // 相邻重复块合并（KF8 常见把一句话拆成多个 div）
        val merged = ArrayList<Spec>(out.size)
        for (sp in out) {
            if (sp is Spec.Text && merged.lastOrNull() is Spec.Text) {
                val last = merged.last() as Spec.Text
                if (last.style == sp.style) {
                    if (last.text.endsWith("—") || sp.text.startsWith("—")) {
                        merged[merged.size - 1] = Spec.Text(last.text + sp.text, sp.style)
                        continue
                    }
                    if (last.text.length < 3 && sp.text.firstOrNull()?.isLetter() == true) {
                        merged[merged.size - 1] = Spec.Text(last.text + sp.text, sp.style)
                        continue
                    }
                }
            }
            merged.add(sp)
        }
        return merged
    }

    private fun findClose(s: String, from: Int, name: String): Int {
        val needle = "</$name"
        val idx = s.indexOf(needle, from, ignoreCase = true)
        if (idx < 0) return s.length
        val gt = s.indexOf('>', idx)
        return if (gt < 0) s.length else gt + 1
    }

    private fun parseTag(s: String, start: Int): Tag? {
        var i = start + 1
        if (i >= s.length) return null
        val closing = s[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == ':' || s[i] == '-' || s[i] == '_')) i++
        if (i == nameStart) return null
        val name = s.substring(nameStart, i).lowercase()

        val attrStart = i
        var quote = ' '
        while (i < s.length) {
            val c = s[i]
            if (quote == ' ') {
                if (c == '"' || c == '\'') quote = c
                else if (c == '>') break
            } else if (c == quote) {
                quote = ' '
            }
            i++
        }
        if (i >= s.length) return null
        val attrs = s.substring(attrStart, i)
        val selfClose = i > start && s[i - 1] == '/'
        return Tag(name, closing, selfClose, attrs, i + 1)
    }

    private fun attr(attrs: String, key: String): String? {
        val re = Regex("(?i)$key\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        val m = re.find(attrs) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
    }

    private fun clean(raw: String): String {
        if (raw.isEmpty()) return ""
        val decoded = decodeEntities(raw)
        return decoded
            .replace('\u00A0', ' ')
            .replace(Regex("[\\s\\u200B\\uFEFF]+"), " ")
            .trim()
    }

    fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENTITY.replace(s) { m ->
            val dec = m.groupValues[1]
            val hex = m.groupValues[2]
            val named = m.groupValues[3]
            when {
                dec.isNotEmpty() -> codePointToString(dec.toIntOrNull() ?: 0)
                hex.isNotEmpty() -> codePointToString(hex.toIntOrNull(16) ?: 0)
                named.isNotEmpty() -> NAMED[named.lowercase()] ?: m.value
                else -> m.value
            }
        }
    }

    private fun codePointToString(cp: Int): String =
        if (cp in 1..0x10FFFF && (cp < 0xD800 || cp > 0xDFFF)) String(Character.toChars(cp)) else ""
}
