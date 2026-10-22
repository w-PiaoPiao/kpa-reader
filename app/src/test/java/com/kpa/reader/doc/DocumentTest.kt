package com.kpa.reader.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTest {

    @Test
    fun `块序列化往返一致`() {
        val blocks = listOf(
            Block.text(0, 10, Block.H1),
            Block.image("img_0.jpg", 10),
            Block.text(11, 25, Block.BODY)
        )
        val doc = Document("0123456789" + " abcdefghijklm", blocks)
        val restored = Document.parseBlocks(doc.serialize())
        assertEquals(blocks, restored)
    }

    @Test
    fun `按字符偏移定位块`() {
        val blocks = listOf(
            Block.text(0, 10),
            Block.image("a.jpg", 10),
            Block.text(10, 20)
        )
        val doc = Document("0123456789012345678x", blocks)
        assertEquals(0, doc.blockIndexAtChar(0))
        assertEquals(0, doc.blockIndexAtChar(9))
        // 图片块锚点与后面的文本块同起点，二分应落到后者（可渲染的文本）
        assertTrue(doc.blockIndexAtChar(10) >= 1)
        assertEquals(2, doc.blockIndexAtChar(19))
    }

    @Test
    fun `纯文本能切出段落块`() {
        val text = "第一段\n\n第二段\n第三段"
        val blocks = Document.blocksFromPlainText(text)
        assertEquals(3, blocks.size)
        assertEquals("第一段", text.substring(blocks[0].start, blocks[0].end))
        assertEquals("第三段", text.substring(blocks[2].start, blocks[2].end))
    }

    @Test
    fun `空文本不崩溃`() {
        assertTrue(Document.blocksFromPlainText("").isEmpty())
        val doc = Document("", emptyList())
        assertEquals(0, doc.blockIndexAtChar(0))
    }

    @Test
    fun `图片块不占用字符区间`() {
        val blocks = listOf(Block.image("x.png", 0), Block.text(0, 5))
        assertEquals(blocks[0].start, blocks[0].end)
    }
}
