package com.kpa.reader.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSplitterTest {

    @Test
    fun `识别标准中文章节标题`() {
        val text = "第一章 起点\n正文一\n\n第二章 转折\n正文二\n\n第三章 结局\n正文三"
        val chapters = ChapterSplitter.split(text)
        assertEquals(3, chapters.size)
        assertEquals("第一章 起点", chapters[0].title)
        assertEquals(0, chapters[0].start)
        assertEquals(text.length, chapters.last().end)
    }

    @Test
    fun `正文句子不会被误判成章节`() {
        // 「他翻到第三章，发现……有一整句标点，长度也超过标题上限
        val text = "他翻到第三章，发现内容全是废话，于是合上书，决定明天再读。\n这是第二段，也是普通正文。"
        val chapters = ChapterSplitter.split(text)
        assertEquals(1, chapters.size)
    }

    @Test
    fun `没有章节时按字数切块`() {
        val text = "字".repeat(30000)
        val chapters = ChapterSplitter.split(text, chunkChars = 10000)
        assertEquals(3, chapters.size)
        assertEquals(text.length, chapters.last().end)
        // 块之间必须首尾相接、不重叠
        for (i in 1 until chapters.size) {
            assertEquals(chapters[i - 1].end, chapters[i].start)
        }
    }

    @Test
    fun `特殊篇名能识别`() {
        val text = "楔子\n开场白\n\n第一章 开始\n内容\n\n番外一 之后\n番外内容"
        val chapters = ChapterSplitter.split(text)
        assertTrue(chapters.size >= 3)
        assertEquals("楔子", chapters[0].title)
    }

    @Test
    fun `字符偏移能定位到章节`() {
        val text = "第一章 A\n内容一\n\n第二章 B\n内容二"
        val chapters = ChapterSplitter.split(text)
        assertEquals(0, ChapterSplitter.chapterIndexAt(chapters, 0))
        assertEquals(1, ChapterSplitter.chapterIndexAt(chapters, text.length - 1))
    }

    @Test
    fun `虚拟分章优先切在换行处`() {
        // 9995 个 a 后紧跟换行：10000 切点应回退到 \n 之后，不把一行劈成两半
        val text = "a".repeat(9995) + "\n" + "b".repeat(10010)
        val chapters = ChapterSplitter.split(text, chunkChars = 10000)
        assertEquals(3, chapters.size)
        assertEquals(9996, chapters[0].end)
        assertEquals('\n', text[chapters[0].end - 1])
        // 其余两块之间没有换行可用，按字数硬切，但必须首尾相接
        for (i in 1 until chapters.size) {
            assertEquals(chapters[i - 1].end, chapters[i].start)
        }
        assertEquals(text.length, chapters.last().end)
    }

    @Test
    fun `二分定位与线性扫描语义等价`() {
        val text = buildString {
            for (i in 1..50) {
                append("第").append(i).append("章 标题\n").append("内容".repeat(30)).append('\n')
            }
        }
        val chapters = ChapterSplitter.split(text)

        fun linear(offset: Int): Int {
            for (i in chapters.indices) if (offset < chapters[i].end) return i
            return chapters.size - 1
        }

        // 章首、章中、章尾、末尾、越界值全扫一遍
        val probes = ArrayList<Int>()
        for (ch in chapters) {
            probes.add(ch.start)
            probes.add((ch.start + ch.end) / 2)
            probes.add(ch.end - 1)
            probes.add(ch.end)
        }
        probes.add(text.length)
        probes.add(text.length + 1000)
        for (off in probes) {
            assertEquals("offset=$off", linear(off), ChapterSplitter.chapterIndexAt(chapters, off))
        }
        // 空目录兜底
        assertEquals(0, ChapterSplitter.chapterIndexAt(emptyList(), 5))
    }
}
