package com.kpa.reader.book

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EPUB 解析的纯逻辑部分：路径归一化与属性解析。
 * 这两块是「网文工具生成的非标准 EPUB」问题的高发区。
 */
class EpubParserTest {

    private val p = EpubParser

    // ---------------- resolvePath ----------------

    @Test
    fun `空基准目录直接透传`() {
        assertEquals("a.html", p.resolvePath("", "a.html"))
    }

    @Test
    fun `相对路径拼上基准目录`() {
        assertEquals("OEBPS/text/c1.xhtml", p.resolvePath("OEBPS", "text/c1.xhtml"))
    }

    @Test
    fun `双点回上一级并归一化`() {
        assertEquals("OEBPS/images/i.jpg", p.resolvePath("OEBPS/text", "../images/i.jpg"))
    }

    @Test
    fun `连续上跳到根之外不越界`() {
        assertEquals("i.jpg", p.resolvePath("OEBPS/text", "../../../../i.jpg"))
    }

    @Test
    fun `当前目录标记被消化`() {
        assertEquals("OEBPS/c.xhtml", p.resolvePath("OEBPS", "./c.xhtml"))
    }

    @Test
    fun `绝对路径去掉开头斜号`() {
        assertEquals("root.xhtml", p.resolvePath("OEBPS", "/root.xhtml"))
    }

    @Test
    fun `锚点片段被剥掉`() {
        assertEquals("OEBPS/c.xhtml", p.resolvePath("OEBPS", "c.xhtml#frag"))
    }

    @Test
    fun `百分号编码会被解码`() {
        assertEquals("OEBPS/c d.xhtml", p.resolvePath("OEBPS", "c%20d.xhtml"))
    }

    @Test
    fun `裸加号是文件名的一部分`() {
        // URLDecoder 会把 + 当空格；zip 内路径的字面 + 就是文件名
        assertEquals("OEBPS/a+b.xhtml", p.resolvePath("OEBPS", "a+b.xhtml"))
    }

    @Test
    fun `编码过的加号才解码`() {
        assertEquals("OEBPS/a+b.xhtml", p.resolvePath("OEBPS", "a%2Bb.xhtml"))
    }

    // ---------------- parseAttrs ----------------

    @Test
    fun `双引号属性`() {
        val a = p.parseAttrs(""" id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"""")
        assertEquals("ncx", a["id"])
        assertEquals("toc.ncx", a["href"])
        assertEquals("application/x-dtbncx+xml", a["media-type"])
    }

    @Test
    fun `单引号属性同样能取到`() {
        val a = p.parseAttrs(""" id='ncx' href='toc.ncx'""")
        assertEquals("ncx", a["id"])
        assertEquals("toc.ncx", a["href"])
    }

    @Test
    fun `属性名大小写归一`() {
        val a = p.parseAttrs(""" ID="x" HRef="y"""")
        assertEquals("x", a["id"])
        assertEquals("y", a["href"])
    }
}
