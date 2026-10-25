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

    @Test
    fun `西文 CP1252 不再解成中文乱码`() {
        // CP1252 的 é=0xE9：GB18030 会把它和后续字节错配出替换符，过去整本解成
        // 「汉字 + U+FFFD」混合乱码；现在解不出干净中文就退回 1252
        val s = "Café été naïve — l'année dernière à Paris"
        val cp1252 = Charset.forName("windows-1252")
        val loaded = TextLoader.decode(s.toByteArray(cp1252))
        assertEquals(s, loaded.text)
        assertEquals("windows-1252", loaded.encoding)
    }

    @Test
    fun `无 BOM 的 UTF-16LE 含英文能被识别`() {
        // 混合文本里 ASCII 的低字节是 0x00，奇偏移 0x00 占比过阈值即判 LE
        val s = "中文测试文本这是中文内容了。The quick brown fox jumps"
        val loaded = TextLoader.decode(s.toByteArray(Charsets.UTF_16LE))
        assertEquals(s, loaded.text)
        assertEquals("UTF-16LE", loaded.encoding)
    }

    @Test
    fun `无 BOM 的 UTF-16BE 含英文能被识别`() {
        val s = "中文测试文本这是中文内容了。The quick brown fox jumps"
        val loaded = TextLoader.decode(s.toByteArray(Charsets.UTF_16BE))
        assertEquals(s, loaded.text)
        assertEquals("UTF-16BE", loaded.encoding)
    }

    @Test
    fun `正常 GBK 中文书不会被误判成西文编码`() {
        // 含全角标点与普通汉字的常规样本，必须仍走 GB18030
        val s = "少年抬起头，望向远方的山峦。那是什么地方？他不知道。"
        val gbk = Charset.forName("GBK")
        val loaded = TextLoader.decode(s.toByteArray(gbk))
        assertEquals(s, loaded.text)
        assertTrue(loaded.encoding.startsWith("GB"))
    }
}
