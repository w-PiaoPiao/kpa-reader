package com.kpa.reader.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextLoaderTest {

    @Test
    fun `UTF-8 文本直通`() {
        val s = "中文测试 abc"
        val loaded = TextLoader.decode(s.toByteArray(Charsets.UTF_8))
        assertEquals(s, loaded.text)
        assertEquals("UTF-8", loaded.encoding)
    }

    @Test
    fun `GBK 文本能识别并正确解码`() {
        val s = "这是一段中文，用来测试编码探测。"
        val gbk = Charset.forName("GBK")
        val loaded = TextLoader.decode(s.toByteArray(gbk))
        assertEquals(s, loaded.text)
        assertTrue(loaded.encoding.startsWith("GB"))
    }

    @Test
    fun `UTF-8 BOM 会被剥掉`() {
        val s = "带 BOM 的内容"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val loaded = TextLoader.decode(bom + s.toByteArray(Charsets.UTF_8))
        assertEquals(s, loaded.text)
        assertEquals("UTF-8", loaded.encoding)
    }

    @Test
    fun `行首全角空格会被清掉`() {
        // 中文 TXT 常自带两字缩进，不清掉会和引擎缩进叠成四字
        val raw = "　　第一段\n　　第二段"
        assertEquals("第一段\n第二段", TextLoader.normalize(raw))
    }

    @Test
    fun `CRLF 统一成 LF`() {
        assertEquals("a\nb\nc", TextLoader.normalize("a\r\nb\rc"))
    }

    @Test
    fun `多余空行被压缩`() {
        assertEquals("a\n\nb", TextLoader.normalize("a\n\n\n\n\nb"))
    }

    @Test
    fun `控制字符与零宽字符被清除`() {
        val raw = "正常\u0000文字\u200B内容\uFEFF"
        assertEquals("正常文字内容", TextLoader.normalize(raw))
    }
}
