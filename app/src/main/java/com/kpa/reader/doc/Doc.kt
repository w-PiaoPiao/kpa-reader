package com.kpa.reader.doc

/**
 * 文档块。
 *
 * 阅读器要同时处理纯文字、标题和插图，所以正文在内部表示成「块序列」。
 * 文本块用 [start]/[end] 指向 [Document.text] 的字符区间 —— 全书纯文本仍然是
 * 进度保存和全文搜索的唯一基准，图片块不占字符。
 */
data class Block(
    val isImage: Boolean,
    val start: Int,
    val end: Int,
    val style: Int = BODY,
    val image: String = ""
) {
    companion object {
        const val BODY = 0
        const val H1 = 1
        const val H2 = 2
        const val H3 = 3
        const val QUOTE = 4

        fun text(start: Int, end: Int, style: Int = BODY) = Block(false, start, end, style)
        fun image(name: String, at: Int) = Block(true, at, at, BODY, name)
    }
}

/**
 * 一部已解析好的书：纯文本 + 块序列。
 *
 * 序列化用极简的行格式（比 JSON 小一个数量级，几万块也只要几百 KB）：
 *   T,start,end,style
 *   I,filename,anchor
 */
class Document(
    val text: String,
    val blocks: List<Block>
) {

    /** 找到包含某字符偏移的块下标（图片块的锚点是前一个文本块的结束位置） */
    fun blockIndexAtChar(offset: Int): Int {
        if (blocks.isEmpty()) return 0
        var lo = 0
        var hi = blocks.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (blocks[mid].start <= offset) {
                ans = mid; lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    fun serialize(): String {
        val sb = StringBuilder(blocks.size * 18)
        for (b in blocks) {
            if (b.isImage) {
                sb.append('I').append(',').append(b.image).append(',').append(b.start).append('\n')
            } else {
                sb.append('T').append(',')
                    .append(b.start).append(',')
                    .append(b.end).append(',')
                    .append(b.style).append('\n')
            }
        }
        return sb.toString()
    }

    companion object {
        fun parseBlocks(raw: String): List<Block> {
            val out = ArrayList<Block>(raw.count { it == '\n' } + 1)
            var i = 0
            val n = raw.length
            while (i < n) {
                var j = i
                while (j < n && raw[j] != '\n') j++
                if (j > i + 1) {
                    val line = raw.substring(i, j)
                    val p = line.split(',')
                    if (p.size >= 2) {
                        when (line[0]) {
                            'T' -> if (p.size >= 4) {
                                out.add(Block.text(p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0,
                                    p[3].toIntOrNull() ?: 0))
                            }
                            'I' -> out.add(Block.image(p[1], p.getOrNull(2)?.toIntOrNull() ?: 0))
                        }
                    }
                }
                i = j + 1
            }
            return out
        }

        /** 纯文本没有块信息时，按空行/换行切段兜底（兼容旧缓存） */
        fun blocksFromPlainText(text: String): List<Block> {
            val out = ArrayList<Block>()
            var lineStart = 0
            val n = text.length
            while (lineStart < n) {
                var nl = text.indexOf('\n', lineStart)
                if (nl < 0) nl = n
                if (nl > lineStart) out.add(Block.text(lineStart, nl))
                lineStart = nl + 1
            }
            if (out.isEmpty() && n > 0) out.add(Block.text(0, n))
            return out
        }
    }
}
