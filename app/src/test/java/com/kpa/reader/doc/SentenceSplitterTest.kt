package com.kpa.reader.doc

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SentenceSplitterTest {

    private fun ends(s: String) = SentenceSplitter.splitEnds(s, 0, s.length)

    @Test
    fun `中文句子按句末标点切开`() {
        val s = "今天天气很好。我们去公园吧！"
        assertArrayEquals(intArrayOf(7, 14), ends(s))
    }

    @Test
    fun `没有标点的段落不拆分`() {
        val s = "这一段没有任何句末标点所以应当保持原样"
        assertArrayEquals(intArrayOf(s.length), ends(s))
    }

    @Test
    fun `连续终止符合并为一句`() {
        val s = "真的吗？！不可能……"
        // "？！" 与 "……" 各自整体作为句末，不能拆成一堆空句
        assertArrayEquals(intArrayOf(5, 10), ends(s))
    }

    @Test
    fun `句末的闭合引号跟着前一句`() {
        val s = "他说：「走吧。」然后离开了。"
        assertArrayEquals(intArrayOf(8, 14), ends(s))
    }

    @Test
    fun `小数点不当作句末`() {
        val s = "版本 1.2 支持 3.5 寸屏"
        assertArrayEquals(intArrayOf(s.length), ends(s))
    }

    @Test
    fun `英文句子按句点加空格切开`() {
        val s = "Hello world. This is fine."
        assertArrayEquals(intArrayOf(12, 26), ends(s))
    }

    @Test
    fun `分号不作为句末`() {
        val s = "春天播种；秋天收获。"
        assertArrayEquals(intArrayOf(10), ends(s))
    }

    @Test
    fun `段尾的空白并进最后一句`() {
        val s = "话说完了。   "
        val e = ends(s)
        // 最后一个边界必须正好是块尾，否则剩余空白会被单独排成一段
        assertArrayEquals(intArrayOf(s.length), e)
    }

    @Test
    fun `空文本返回空数组`() {
        assertArrayEquals(IntArray(0), SentenceSplitter.splitEnds("", 0, 0))
    }

    @Test
    fun `最后一个边界恒等于区间长度`() {
        val s = "第一句。第二句！第三句？尾巴"
        val e = ends(s)
        assertArrayEquals(intArrayOf(4, 8, 12, 14), e)
        assert(e.last() == s.length)
    }

    @Test
    fun `带偏移区间切分正确`() {
        val s = "前言。第一句。第二句。"
        val e = SentenceSplitter.splitEnds(s, 3, s.length)
        // 返回的是相对 start 的偏移："第一句。" "第二句。" 各 4 字
        assertArrayEquals(intArrayOf(4, 8), e)
    }
}
