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
}
