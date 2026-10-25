package com.kpa.reader.book

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MOBI 解压的纯字节逻辑：PalmDoc LZ77、记录尾部剥离、大端读取。
 * 样本全部手工按 AportisDoc 规范构造。
 */
class MobiParserTest {

    private val m = MobiParser

    private fun decompress(vararg bytes: Int): ByteArray {
        val src = ByteArray(bytes.size) { bytes[it].toByte() }
        val out = MobiParser.PalmDocOutput(64)
        m.decompressPalmDoc(src, out)
        return out.toByteArray()
    }

    @Test
    fun `纯 ASCII 字面量直通`() {
        assertArrayEquals("Hello".toByteArray(), decompress(*"Hello".map { it.code }.toIntArray()))
    }

    @Test
    fun `短字面量块 1 到 8`() {
        // 0x02 = 后跟 2 个字面字节
        assertArrayEquals("AB".toByteArray(), decompress(0x02, 'A'.code, 'B'.code))
    }

    @Test
    fun `回引复制已解压内容`() {
        // "abc" + 回引 dist=3 len=3 → pair=(3<<3)|0=0x18，首字节须落在 0x80-0xBF → 0x80,0x18
        assertArrayEquals(
            "abcabc".toByteArray(),
            decompress('a'.code, 'b'.code, 'c'.code, 0x80, 0x18)
        )
    }

    @Test
    fun `重叠回引按滑动窗口语义展开`() {
        // "ab" + 回引 dist=2 len=6 → pair=(2<<3)|(6-3)=0x13
        // 重复字符 run 就靠重叠展开；快照式实现会截断成 "abab"
        assertArrayEquals(
            "abababab".toByteArray(),
            decompress('a'.code, 'b'.code, 0x80, 0x13)
        )
    }

    @Test
    fun `高位字节解成空格加翻转`() {
        // 0xC0 → ' ' + (0xC0 xor 0x80)='@'；0xE9 → ' ' + 'i'
        assertArrayEquals(" @ i".toByteArray(), decompress(0xC0, 0xE9))
    }

    @Test
    fun `距离 0 的回引被跳过`() {
        assertArrayEquals(
            "xy".toByteArray(),
            decompress('x'.code, 'y'.code, 0x80, 0x00)
        )
    }

    // ---------------- stripTrailing ----------------

    @Test
    fun `超出 recordSize 的部分剥掉`() {
        val data = ByteArray(4100) { (it % 251).toByte() }
        val stripped = m.stripTrailing(data, 4096, 0)
        assertEquals(4096, stripped.size)
        assertArrayEquals(data.copyOf(4096), stripped)
    }

    @Test
    fun `flags bit0 给出尾部重叠长度`() {
        // 末字节 0x01 → (1 and 3)+1 = 2，剥 2 字节
        val data = byteArrayOf(0x61, 0x62, 0x63, 0x01)
        assertArrayEquals(byteArrayOf(0x61, 0x62), m.stripTrailing(data, 4096, 1))
    }

    @Test
    fun `剥除量不小于数据本身时原样返回`() {
        val data = byteArrayOf(0x61, 0x01)
        assertArrayEquals(data, m.stripTrailing(data, 4096, 1))
    }

    @Test
    fun `无标记且未超限不动`() {
        val data = byteArrayOf(0x61, 0x62, 0x63)
        assertArrayEquals(data, m.stripTrailing(data, 4096, 0))
    }

    @Test
    fun `空数据原样返回`() {
        assertEquals(0, m.stripTrailing(ByteArray(0), 4096, 1).size)
    }

    // ---------------- be16 / be32 ----------------

    @Test
    fun `大端读取`() {
        val b = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertEquals(0x1234, m.be16(b, 0))
        assertEquals(0x3456, m.be16(b, 1))
        assertEquals(0x12345678, m.be32(b, 0))
    }

    @Test
    fun `越界返回 0 不抛异常`() {
        val b = byteArrayOf(0x12, 0x34)
        assertEquals(0, m.be16(b, 1))
        assertEquals(0, m.be16(b, 99))
        assertEquals(0, m.be32(b, 0))
    }
}
