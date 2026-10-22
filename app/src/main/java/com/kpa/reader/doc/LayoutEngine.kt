package com.kpa.reader.doc

import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan

/**
 * 分页引擎（支持文本块 + 图片块）。
 *
 * 依然是「逐块流式排版」：每次只排一个块，量出高度累加到当前页，放不下就断页；
 * 长段落用 getLineStart 在行边界切开，剩余部分接到下一页且不再做首行缩进。
 * 图片按页宽等比缩放，比整页还高的图单独占一页。
 *
 * 所有 Paint 都在构造时**深拷贝**一份：分页会被丢到后台线程跑，
 * 如果和界面线程共用同一个 Paint 实例，改字号时会撞车。
 */
class LayoutEngine(
    private val text: String,
    bodyPaint: TextPaint,
    private val width: Int,
    private val height: Int,
    private val lineSpacingMult: Float,
    private val indentPx: Float,
    private val paraSpacingPx: Float,
    private val imageMetrics: (String) -> Pair<Int, Int>?
) {

    private val body = TextPaint(bodyPaint)
    private val h1Paint = TextPaint(body).apply {
        textSize = body.textSize * 1.42f
        isFakeBoldText = true
    }
    private val h2Paint = TextPaint(body).apply {
        textSize = body.textSize * 1.22f
        isFakeBoldText = true
    }
    private val h3Paint = TextPaint(body).apply {
        textSize = body.textSize * 1.08f
        isFakeBoldText = true
    }
    private val quotePaint = TextPaint(body).apply {
        alpha = (bodyPaint.alpha * 0.82f).toInt().coerceIn(0, 255)
    }

    sealed class Item {
        /** (from, to) 是块内字符偏移 */
        data class TextSeg(
            val blockIndex: Int,
            val from: Int,
            val to: Int,
            val indent: Boolean,
            val style: Int,
            val y: Int
        ) : Item()

        data class Img(
            val blockIndex: Int,
            val name: String,
            val w: Int,
            val h: Int,
            val y: Int
        ) : Item()
    }

    /** startBlock/startChar 是页首位置，用于把页还原成字符偏移 */
    data class Page(val startBlock: Int, val startChar: Int, val items: List<Item>)

    fun paintFor(style: Int): TextPaint = when (style) {
        Block.H1 -> h1Paint
        Block.H2 -> h2Paint
        Block.H3 -> h3Paint
        Block.QUOTE -> quotePaint
        else -> body
    }

    private fun gapBefore(style: Int): Float = when (style) {
        Block.H1 -> body.textSize * 0.7f
        Block.H2 -> body.textSize * 0.55f
        Block.H3 -> body.textSize * 0.4f
        else -> paraSpacingPx
    }

    private fun gapAfter(style: Int): Float = when (style) {
        Block.H1 -> body.textSize * 0.5f
        Block.H2 -> body.textSize * 0.35f
        else -> 0f
    }

    fun buildLayout(style: Int, blockStart: Int, blockEnd: Int, fromChar: Int): StaticLayout {
        val s = (blockStart + fromChar).coerceIn(0, text.length)
        val e = blockEnd.coerceIn(s, text.length)
        val paint = paintFor(style)
        val cs: CharSequence = if (fromChar == 0 && style == Block.BODY && indentPx > 0.5f) {
            SpannableString(text.substring(s, e)).apply {
                if (length > 0) {
                    setSpan(
                        LeadingMarginSpan.Standard(indentPx.toInt(), 0),
                        0, length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        } else {
            text.substring(s, e)
        }
        return StaticLayout.Builder.obtain(cs, 0, cs.length, paint, width.coerceAtLeast(1))
            .setLineSpacing(0f, lineSpacingMult)
            // 必须包含首行 top 与末行 bottom 的 padding：
            // includePad=false 时 layout.height 比真实绘制范围小，
            // 累加出来的页高会让最后一行压到页脚上
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    fun paginate(blocks: List<Block>, fromBlock: Int, fromChar: Int): List<Page> {
        if (width <= 0 || height <= 0) return listOf(Page(fromBlock, fromChar, emptyList()))

        val pages = ArrayList<Page>()
        var items = ArrayList<Item>()
        var pageBlock = fromBlock
        var pageChar = fromChar
        var y = 0f

        fun flush(endBlock: Int, endChar: Int) {
            if (items.isNotEmpty()) pages.add(Page(pageBlock, pageChar, items))
            items = ArrayList()
            pageBlock = endBlock
            pageChar = endChar
            y = 0f
        }

        var bi = fromBlock.coerceAtLeast(0)
        var charIn = if (bi == fromBlock) fromChar.coerceAtLeast(0) else 0

        while (bi < blocks.size) {
            val b = blocks[bi]

            if (b.isImage) {
                val dim = imageMetrics(b.image)
                if (dim == null) { bi++; charIn = 0; continue }
                val (dw, dh) = fitImage(dim.first, dim.second)
                val gap = if (items.isEmpty()) 0f else paraSpacingPx
                if (y + gap + dh > height && items.isNotEmpty()) {
                    flush(bi, charIn)
                    continue
                }
                items.add(Item.Img(bi, b.image, dw, dh, (y + gap).toInt()))
                y += gap + dh + paraSpacingPx
                bi++; charIn = 0
                continue
            }

            if (b.start + charIn >= b.end) { bi++; charIn = 0; continue }

            val layout = buildLayout(b.style, b.start, b.end, charIn)
            val gap = if (items.isEmpty()) 0f else gapBefore(b.style)
            val need = layout.height + gap

            if (y + need + gapAfter(b.style) <= height) {
                items.add(Item.TextSeg(bi, charIn, b.end - b.start, charIn == 0 && b.style == Block.BODY, b.style, (y + gap).toInt()))
                y += need + gapAfter(b.style)
                bi++; charIn = 0
                continue
            }

            val avail = height - y - gap
            var fit = linesThatFit(layout, avail)
            if (fit <= 0 && items.isEmpty()) fit = 1
            if (fit <= 0) {
                flush(bi, charIn)
                continue
            }

            if (fit >= layout.lineCount) {
                items.add(Item.TextSeg(bi, charIn, b.end - b.start, charIn == 0 && b.style == Block.BODY, b.style, (y + gap).toInt()))
                y += need + gapAfter(b.style)
                bi++; charIn = 0
                continue
            }

            val cut = layout.getLineStart(fit)
            if (cut <= 0) {
                flush(bi, charIn)
                continue
            }
            items.add(Item.TextSeg(bi, charIn, charIn + cut, charIn == 0 && b.style == Block.BODY, b.style, (y + gap).toInt()))
            flush(bi, charIn + cut)
            charIn += cut
        }

        if (items.isNotEmpty()) pages.add(Page(pageBlock, pageChar, items))
        if (pages.isEmpty()) pages.add(Page(fromBlock, fromChar, emptyList()))
        return pages
    }

    /** 只在 [fromBlock, endBlock) 范围内分页（章节边界） */
    fun paginateRange(blocks: List<Block>, fromBlock: Int, fromChar: Int, endBlock: Int): List<Page> {
        val end = endBlock.coerceIn(0, blocks.size)
        if (end <= fromBlock) return listOf(Page(fromBlock, fromChar, emptyList()))
        return paginate(blocks.subList(0, end), fromBlock, fromChar)
    }

    private fun fitImage(rawW: Int, rawH: Int): Pair<Int, Int> {
        if (rawW <= 0 || rawH <= 0) return width to (height / 2)
        var w = width
        var h = (width.toLong() * rawH / rawW).toInt()
        val maxH = (height * 0.96f).toInt()
        if (h > maxH) {
            h = maxH
            w = (maxH.toLong() * rawW / rawH).toInt().coerceAtMost(width)
        }
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    private fun linesThatFit(layout: StaticLayout, avail: Float): Int {
        if (avail <= 0f) return 0
        var h = 0f
        for (k in 0 until layout.lineCount) {
            val lh = (layout.getLineBottom(k) - layout.getLineTop(k)).toFloat()
            if (h + lh > avail + 0.5f) return k
            h += lh
        }
        return layout.lineCount
    }
}
