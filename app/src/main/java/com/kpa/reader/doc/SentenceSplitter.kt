package com.kpa.reader.doc

/**
 * 句子切分（供「按句分段」模式使用）。
 *
 * 目标是把一个自然段切成若干句，让排版引擎一句一段地排。
 * 只处理边界识别，不改变字符本身 —— 切分结果是一串**块内相对偏移**，
 * 所以全书字符偏移（进度、书签、搜索的基准）完全不受影响。
 */
object SentenceSplitter {

    /** 句末标点。分号故意不在内：中文分号多用于并列长句，切断会把句子打得太碎。 */
    private fun isTerminator(t: CharSequence, i: Int, start: Int, end: Int): Boolean {
        return when (t[i]) {
            '。', '．', '！', '？', '!', '?', '…' -> true
            // 英文句点要额外判断，否则 "3.5 寸" "v1.2" 会被切开
            '.' -> {
                val next = if (i + 1 < end) t[i + 1] else ' '
                val nextOk = next.isWhitespace() || isCloser(next)
                val prevDigit = i > start && t[i - 1].isDigit()
                nextOk && !prevDigit
            }
            else -> false
        }
    }

    /** 句末后可能紧跟的闭合符号，应归到前一句里 */
    private fun isCloser(c: Char): Boolean = when (c) {
        '”', '’', '」', '』', '）', ')', '】', '》', '〉', '"', '\'' -> true
        else -> false
    }

    private fun hasContent(t: CharSequence, from: Int, to: Int): Boolean {
        for (k in from until to) {
            if (!t[k].isWhitespace()) return true
        }
        return false
    }

    /**
     * 返回句末偏移数组，元素是**相对 [start] 的偏移**，升序，最后一个恒等于 `end - start`。
     *
     * 段落里没有任何终止符时返回单元素数组，等价于不拆分。
     */
    fun splitEnds(text: CharSequence, start: Int, end: Int): IntArray {
        if (end <= start) return IntArray(0)
        val len = end - start
        val out = ArrayList<Int>(8)

        var segStart = start
        var i = start
        while (i < end) {
            if (!isTerminator(text, i, start, end)) {
                i++
                continue
            }
            var j = i + 1
            // 吞掉连续终止符："？！" "……" "。。。"
            while (j < end && isTerminator(text, j, start, end)) j++
            // 吞掉跟在句末的闭合引号、括号
            while (j < end && isCloser(text[j])) j++

            if (!hasContent(text, segStart, j)) {
                // 这一"句"只有空白，不断开，继续往后找
                i = j
                continue
            }
            out.add(j - start)
            segStart = j
            i = j
        }

        // 收尾：保证最后一句正好吃到块尾，否则剩余空白会被当成一段再排一次
        if (out.isEmpty()) {
            out.add(len)
        } else if (out.last() < len) {
            if (hasContent(text, start + out.last(), end)) {
                out.add(len)
            } else {
                out[out.size - 1] = len
            }
        }
        return out.toIntArray()
    }
}
