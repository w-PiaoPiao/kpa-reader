package com.kpa.reader.book

import com.kpa.reader.data.Chapter

/**
 * 章节识别。
 *
 * 中文 TXT 没有结构化目录，只能靠标题行的形态猜。为了避免把正文句子误判成标题，
 * 除了正则还要满足：整行短（<= 42 字）、不含句读标点（。！？；，、）。
 * 识别不到章节时按字数切虚拟章节，保证分页和跳转仍然可用。
 */
object ChapterSplitter {

    private const val MAX_TITLE_LEN = 42
    private const val CHUNK_CHARS = 12000

    private const val NUM = "0-9０-９零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟"

    private val RES = listOf(
        // 第X章 / 第 X 回 / 第123节（可带「正文」前缀）
        Regex("^(?:正文|VIP|vip)?\\s*第\\s*[$NUM]{1,12}\\s*[章节節回卷部篇集话話幕折]"),
        // 卷X / 卷一
        Regex("^卷\\s*[$NUM]{1,4}"),
        // 特殊篇名（允许后面跟序号：「番外一」「外传2」）
        Regex("^(序章|序言|自序|代序|序|楔子|引子|引言|前言|后记|後記|尾声|尾聲|终章|終章|结局|結局|大结局|番外|外传|外傳|附录|附錄|完本感言|终)(\\s|$|[:：、·—\\-0-9０-９一二三四五六七八九十]|[$NUM])"),
        // 英文章节
        Regex("^(chapter|CHAPTER|Chapter|part|PART|Part|prologue|PROLOGUE|Prologue|epilogue|EPILOGUE|Epilogue|book|BOOK)\\b[\\s\\dIVXLC:：.\\-]*$")
    )

    private val SENTENCE_PUNCT = charArrayOf('。', '！', '？', '；', '，', '、', '”', '』')

    fun split(text: String, chunkChars: Int = CHUNK_CHARS): List<Chapter> {
        if (text.isEmpty()) return listOf(Chapter("正文", 0, 0))

        val marks = ArrayList<Pair<String, Int>>()   // 标题 → 行首偏移
        var lineStart = 0
        val n = text.length
        while (lineStart <= n) {
            val nl = text.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) n else nl
            // 先按长度筛一遍再 substring：一部 77 万字的书有几万行，
            // 每行都切一次字符串会产生大量临时对象和 GC 压力
            val rawLen = lineEnd - lineStart
            if (rawLen in 1..(MAX_TITLE_LEN + 12)) {
                val line = text.substring(lineStart, lineEnd).trim()
                if (line.isNotEmpty() && line.length <= MAX_TITLE_LEN && isTitle(line)) {
                    marks.add(line to lineStart)
                }
            }
            if (nl < 0) break
            lineStart = nl + 1
        }

        // 标题太少说明没识别出目录，走虚拟分章
        if (marks.size < 2) return chunked(text, chunkChars)

        val chapters = ArrayList<Chapter>()
        // 第一个章节标题之前的内容单独成章
        val firstStart = marks[0].second
        if (firstStart > 0) {
            val head = text.substring(0, firstStart)
            if (head.trim().length > 30) {
                chapters.add(Chapter("卷首", 0, firstStart))
            }
        }
        for (k in marks.indices) {
            val s = marks[k].second
            val e = if (k + 1 < marks.size) marks[k + 1].second else n
            if (e > s) chapters.add(Chapter(marks[k].first, s, e))
        }
        return if (chapters.isEmpty()) chunked(text, chunkChars) else chapters
    }

    private fun isTitle(line: String): Boolean {
        for (c in SENTENCE_PUNCT) if (line.indexOf(c) >= 0) return false
        // 纯数字行、纯符号行不要
        if (line.none { it.isLetter() || it in '0'..'9' }) return false
        return RES.any { it.containsMatchIn(line) }
    }

    private fun chunked(text: String, chunkChars: Int): List<Chapter> {
        val out = ArrayList<Chapter>()
        var s = 0
        var idx = 1
        val n = text.length
        while (s < n) {
            var e = (s + chunkChars).coerceAtMost(n)
            if (e < n) {
                // 尽量切在换行处，避免把一句话劈开
                val nl = text.lastIndexOf('\n', e)
                if (nl > s + chunkChars / 2) e = nl + 1
            }
            out.add(Chapter("第 $idx 节", s, e))
            s = e
            idx++
        }
        if (out.isEmpty()) out.add(Chapter("正文", 0, n))
        return out
    }

    /** 在全书里定位某个字符偏移属于哪一章（chapters 的 end 单调递增，二分） */
    fun chapterIndexAt(chapters: List<Chapter>, offset: Int): Int {
        if (chapters.isEmpty()) return 0
        var lo = 0
        var hi = chapters.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offset < chapters[mid].end) hi = mid else lo = mid + 1
        }
        return lo
    }
}
