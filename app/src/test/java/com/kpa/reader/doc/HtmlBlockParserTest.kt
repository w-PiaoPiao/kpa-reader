package com.kpa.reader.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlBlockParserTest {

    @Test
    fun `段落与标题被正确分层`() {
        val html = """<h1>第一章</h1><p>第一段</p><p>第二段</p>"""
        val specs = HtmlBlockParser.parse(html)
        assertEquals(3, specs.size)
        assertEquals(Block.H1, (specs[0] as HtmlBlockParser.Spec.Text).style)
        assertEquals(Block.BODY, (specs[1] as HtmlBlockParser.Spec.Text).style)
        assertEquals("第一段", (specs[1] as HtmlBlockParser.Spec.Text).text)
    }

    @Test
    fun `XML 声明不会被当成正文`() {
        val html = """<?xml version="1.0" encoding="utf-8"?><html><body><p>正文</p></body></html>"""
        val specs = HtmlBlockParser.parse(html)
        assertEquals(1, specs.size)
        assertEquals("正文", (specs[0] as HtmlBlockParser.Spec.Text).text)
    }

    @Test
    fun `图片被识别为独立块`() {
        val html = """<div><img src="images/a.jpg" alt="x"/></div>"""
        val specs = HtmlBlockParser.parse(html)
        assertEquals(1, specs.size)
        assertTrue(specs[0] is HtmlBlockParser.Spec.Img)
        assertEquals("images/a.jpg", (specs[0] as HtmlBlockParser.Spec.Img).src)
    }

    @Test
    fun `SVG image 标签也能取到路径`() {
        val html = """<svg><image xlink:href="cover.jpg"/></svg>"""
        val specs = HtmlBlockParser.parse(html)
        assertEquals(1, specs.size)
        assertEquals("cover.jpg", (specs[0] as HtmlBlockParser.Spec.Img).src)
    }

    @Test
    fun `script 与 style 内容被丢弃`() {
        val html = """<style>.a{color:red}</style><script>var x=1</script><p>只剩这段</p>"""
        val specs = HtmlBlockParser.parse(html)
        assertEquals(1, specs.size)
        assertEquals("只剩这段", (specs[0] as HtmlBlockParser.Spec.Text).text)
    }

    @Test
    fun `HTML 实体被解码`() {
        assertEquals("a&b<c", HtmlBlockParser.decodeEntities("a&amp;b&lt;c"))
        assertEquals("·", HtmlBlockParser.decodeEntities("&middot;"))
        assertEquals("中", HtmlBlockParser.decodeEntities("&#20013;"))
    }

    @Test
    fun `未闭合标签不会丢内容`() {
        // MOBI 里的 HTML 常年缺闭合标签
        val html = """<p>第一段<p>第二段<div>第三段"""
        val specs = HtmlBlockParser.parse(html)
        val texts = specs.filterIsInstance<HtmlBlockParser.Spec.Text>().map { it.text }
        assertEquals(listOf("第一段", "第二段", "第三段"), texts)
    }

    @Test
    fun `闭标签后样式恢复成正文`() {
        val html = """<h2>标题一</h2><p>段一</p><h2>标题二</h2><p>段二</p>"""
        val specs = HtmlBlockParser.parse(html)
        val styles = specs.filterIsInstance<HtmlBlockParser.Spec.Text>().map { it.style }
        assertEquals(listOf(Block.H2, Block.BODY, Block.H2, Block.BODY), styles)
    }

    @Test
    fun `嵌套标题逐层恢复样式`() {
        val html = """<h1><h2>双标题</h2>外层</h1><p>正文</p>"""
        val specs = HtmlBlockParser.parse(html)
        val styles = specs.filterIsInstance<HtmlBlockParser.Spec.Text>().map { it.style }
        assertEquals(listOf(Block.H2, Block.H1, Block.BODY), styles)
    }

    @Test
    fun `blockquote 闭合后回到正文样式`() {
        val html = """<blockquote>引用内容</blockquote><p>正文</p>"""
        val specs = HtmlBlockParser.parse(html)
        val styles = specs.filterIsInstance<HtmlBlockParser.Spec.Text>().map { it.style }
        assertEquals(listOf(Block.QUOTE, Block.BODY), styles)
    }
}
