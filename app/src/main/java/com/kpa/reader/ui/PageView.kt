package com.kpa.reader.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.kpa.reader.data.ReaderSettings
import com.kpa.reader.doc.Document
import com.kpa.reader.doc.LayoutEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 单页阅读视图：自绘 Canvas。
 *
 * 一页由「文本段 + 图片」组成，文本用 StaticLayout 排好直接画，图片按页宽等比缩放后居中。
 *
 * 性能上做了三件事：
 * 1. 分页丢到后台线程（一章几万字要排几百页，同步跑会明显卡住换章）；
 * 2. 图片解码也在后台，绘制时先用占位方块，解好再重绘；
 * 3. 位图走 LruCache，尺寸探测只读图片头（inJustDecodeBounds）。
 */
class PageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onBoundary(forward: Boolean): Boolean
        fun onTapCenter()
        fun onPageChanged(page: Int, total: Int)
        /** 开始/结束重新分页，界面可以据此显示「排版中」 */
        fun onLayoutState(laying: Boolean) {}
    }

    var listener: Listener? = null

    /** 由 Activity 注入，用于承载后台分页与图片解码 */
    var scope: CoroutineScope? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        hinting = Paint.HINTING_ON
    }
    private val chromePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val ghostPaint = Paint()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var settings = ReaderSettings()

    private var doc: Document? = null
    private var imageDir: java.io.File? = null

    private var chapterStart = 0
    private var chapterEnd = 0
    private var chapterLabel = ""

    private var engine: LayoutEngine? = null
    private var pages: List<LayoutEngine.Page> = emptyList()
    private var pageIndex = 0

    private class Rendered(val layout: StaticLayout, val y: Int)
    private var renderedText: List<Rendered> = emptyList()
    private var renderedImages: List<LayoutEngine.Item.Img> = emptyList()

    private val bitmapCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceIn(4096, 65536)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val sizeCache = HashMap<String, Pair<Int, Int>>()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    private var ghost: Bitmap? = null
    private var ghostX = 0f
    private var ghostAlpha = 1f
    private var ghostAnim: ValueAnimator? = null
    private var layoutJob: Job? = null

    /** 分页还没完成时的临时位置，保证进度计算不跳变 */
    private var pendingChar = -1

    var overallPercent: Float = 0f
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    // ---------------- 配置 ----------------

    fun configure(settings: ReaderSettings, @Suppress("UNUSED_PARAMETER") bookTitle: String) {
        val changed = this.settings != settings
        this.settings = settings
        applyPaints()
        if (changed && doc != null) {
            // 字号/字体变了要重建引擎：标题用的 Paint 是正文 Paint 的副本，不重建不会跟着变
            rebuildEngine()
            relayout(currentCharOffset())
        } else {
            invalidate()
        }
    }

    private fun applyPaints() {
        paint.typeface = if (settings.serif) Typeface.SERIF else Typeface.SANS_SERIF
        paint.textSize = settings.fontSizeSp * scaledDensity
        paint.color = settings.theme.fg
        chromePaint.typeface = Typeface.SANS_SERIF
        chromePaint.textSize = 10.5f * scaledDensity
        chromePaint.color = settings.theme.sub
        placeholderPaint.color = settings.theme.sub
        placeholderPaint.alpha = 40
    }

    fun setDocument(document: Document, imgDir: java.io.File?) {
        doc = document
        imageDir = imgDir
        sizeCache.clear()
        bitmapCache.evictAll()
    }

    fun setChapter(blockStart: Int, blockEnd: Int, label: String, absoluteChar: Int) {
        chapterStart = blockStart.coerceAtLeast(0)
        chapterEnd = blockEnd.coerceAtLeast(chapterStart)
        chapterLabel = label
        pages = emptyList()
        pageIndex = 0
        renderedText = emptyList()
        renderedImages = emptyList()
        ghost?.recycle(); ghost = null; ghostAlpha = 1f; ghostX = 0f
        rebuildEngine()
        relayout(absoluteChar)
    }

    private fun rebuildEngine() {
        val w = contentWidth()
        val h = contentHeight()
        val d = doc ?: return
        if (w <= 0 || h <= 0) { engine = null; return }
        engine = LayoutEngine(
            text = d.text,
            bodyPaint = paint,
            width = w,
            height = h,
            lineSpacingMult = settings.lineSpacingMult,
            indentPx = paint.textSize * 2f,
            paraSpacingPx = paint.textSize * settings.paragraphSpacingEm,
            imageMetrics = { name -> imageSizeOf(name) }
        )
    }

    /** [absoluteChar] 是全书字符偏移；分页在后台线程完成 */
    private fun relayout(absoluteChar: Int) {
        val d = doc ?: return
        val blocks = d.blocks
        if (blocks.isEmpty() || chapterEnd <= chapterStart) {
            pages = emptyList(); renderedText = emptyList(); renderedImages = emptyList()
            invalidate(); return
        }
        val e = engine ?: run { rebuildEngine(); engine } ?: return

        pendingChar = absoluteChar
        val startBlock = d.blockIndexAtChar(absoluteChar.coerceAtLeast(0))
            .coerceIn(chapterStart, (chapterEnd - 1).coerceAtLeast(chapterStart))
        val startChar = (absoluteChar - blocks[startBlock].start).coerceAtLeast(0)

        layoutJob?.cancel()
        val sc = scope
        if (sc == null) {
            // 没有注入 scope 时退回同步，保证极端情况下仍可用
            pages = e.paginateRange(blocks, startBlock, startChar, chapterEnd)
            pageIndex = 0
            pendingChar = -1
            buildPage()
            invalidate()
            listener?.onPageChanged(pageIndex, pages.size)
            return
        }

        listener?.onLayoutState(true)
        layoutJob = sc.launch {
            val result = withContext(Dispatchers.Default) {
                e.paginateRange(blocks, startBlock, startChar, chapterEnd)
            }
            pages = result
            pageIndex = 0
            pendingChar = -1
            buildPage()
            invalidate()
            listener?.onLayoutState(false)
            listener?.onPageChanged(pageIndex, pages.size)
        }
    }

    fun goToCharOffset(absoluteChar: Int) {
        relayout(absoluteChar)
    }

    // ---------------- 翻页 ----------------

    fun nextPage(): Boolean {
        if (pages.isEmpty()) return false
        if (pageIndex + 1 >= pages.size) return listener?.onBoundary(true) == true
        captureGhost()
        pageIndex++
        buildPage()
        startFlip(forward = true)
        listener?.onPageChanged(pageIndex, pages.size)
        return true
    }

    fun prevPage(): Boolean {
        if (pages.isEmpty()) return false
        if (pageIndex - 1 < 0) return listener?.onBoundary(false) == true
        captureGhost()
        pageIndex--
        buildPage()
        startFlip(forward = false)
        listener?.onPageChanged(pageIndex, pages.size)
        return true
    }

    fun currentCharOffset(): Int {
        val p = pages.getOrNull(pageIndex)
        if (p == null) return pendingChar.coerceAtLeast(0)
        val b = doc?.blocks?.getOrNull(p.startBlock) ?: return pendingChar.coerceAtLeast(0)
        return b.start + p.startChar
    }

    fun pageOrdinal(): Int = pageIndex
    fun pageCount(): Int = pages.size
    fun chapterName(): String = chapterLabel

    // ---------------- 排版/绘制 ----------------

    private fun buildPage() {
        val d = doc ?: return
        val e = engine ?: return
        val page = pages.getOrNull(pageIndex) ?: run {
            renderedText = emptyList(); renderedImages = emptyList(); return
        }
        val texts = ArrayList<Rendered>(page.items.size)
        val imgs = ArrayList<LayoutEngine.Item.Img>(2)
        for (item in page.items) {
            when (item) {
                is LayoutEngine.Item.TextSeg -> {
                    val b = d.blocks.getOrNull(item.blockIndex) ?: continue
                    val layout = e.buildLayout(item.style, b.start, b.end, item.from)
                    texts.add(Rendered(layout, item.y))
                }
                is LayoutEngine.Item.Img -> imgs.add(item)
            }
        }
        renderedText = texts
        renderedImages = imgs
        preloadImages(imgs)
    }

    private fun contentWidth(): Int =
        width - (settings.padHdp * density).roundToInt() * 2

    private fun topInset(): Float =
        settings.padVdp * density + if (settings.showStatusBar) chromePaint.textSize * 1.9f else 0f

    private fun bottomInset(): Float =
        settings.padVdp * density + if (settings.showStatusBar) chromePaint.textSize * 2.1f else 0f

    private fun contentHeight(): Int {
        val reserve = paint.fontSpacing * 0.15f
        return (height - topInset() - bottomInset() - reserve).roundToInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (doc != null && chapterEnd > chapterStart) {
            val keep = currentCharOffset()
            rebuildEngine()
            relayout(keep)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(settings.theme.bg)
        val padH = (settings.padHdp * density).roundToInt()

        canvas.save()
        canvas.clipRect(
            0f, topInset().toInt().toFloat(),
            width.toFloat(), (topInset() + contentHeight()).toInt().toFloat()
        )
        canvas.translate(padH.toFloat(), topInset())

        for (r in renderedText) {
            canvas.save()
            canvas.translate(0f, r.y.toFloat())
            r.layout.draw(canvas)
            canvas.restore()
        }

        for (img in renderedImages) {
            val left = (contentWidth() - img.w) / 2f
            val dst = RectF(left, img.y.toFloat(), left + img.w, img.y + img.h.toFloat())
            val bmp = bitmapCache.get(img.name)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, dst, imagePaint)
            } else {
                // 还没解出来时画个占位，避免一次性 decode 卡住绘制
                canvas.drawRoundRect(dst, 8f, 8f, placeholderPaint)
            }
        }

        canvas.restore()
        drawChrome(canvas, padH)

        ghost?.let { bmp ->
            if (ghostAlpha > 0.01f) {
                ghostPaint.alpha = (ghostAlpha * 255).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.translate(ghostX, 0f)
                canvas.drawBitmap(bmp, 0f, 0f, ghostPaint)
                canvas.restore()
            }
        }
    }

    private fun drawChrome(canvas: Canvas, padH: Int) {
        if (!settings.showStatusBar) return
        val maxW = (width - padH * 2).toFloat()
        val headerBase = (settings.padVdp * density) + chromePaint.textSize
        if (chapterLabel.isNotEmpty()) {
            canvas.drawText(ellipsize(chapterLabel, maxW), padH.toFloat(), headerBase, chromePaint)
        }
        val footerY = height - (settings.padVdp * density) - chromePaint.textSize * 0.25f
        val pageTxt = "${pageIndex + 1} / ${pages.size}"
        canvas.drawText(pageTxt, padH.toFloat(), footerY, chromePaint)

        val pctText = "${(overallPercent * 100f).roundToInt().coerceIn(0, 100)}%"
        canvas.drawText(pctText, width - padH - chromePaint.measureText(pctText), footerY, chromePaint)

        // 页脚细线 + 已读部分高亮，一眼能看出读到全书的哪个位置
        val lineY = footerY - chromePaint.textSize * 1.35f
        val oldAlpha = chromePaint.alpha
        chromePaint.alpha = 40
        canvas.drawRect(padH.toFloat(), lineY, (width - padH).toFloat(), lineY + 1.5f, chromePaint)
        chromePaint.alpha = 150
        val filled = (width - padH * 2) * overallPercent.coerceIn(0f, 1f)
        if (filled > 0f) {
            canvas.drawRect(padH.toFloat(), lineY, padH + filled, lineY + 1.5f, chromePaint)
        }
        chromePaint.alpha = oldAlpha
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (chromePaint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 2 && chromePaint.measureText("$t…") > maxWidth) {
            t = t.substring(0, t.length - 1)
        }
        return "$t…"
    }

    // ---------------- 图片 ----------------

    private fun imageSizeOf(name: String): Pair<Int, Int>? {
        sizeCache[name]?.let { return it }
        val dir = imageDir ?: return null
        val f = java.io.File(dir, name)
        if (!f.exists()) return null
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.absolutePath, opt) }
        if (opt.outWidth <= 0 || opt.outHeight <= 0) return null
        val sz = opt.outWidth to opt.outHeight
        sizeCache[name] = sz
        return sz
    }

    private fun decodeBitmap(name: String): Bitmap? {
        bitmapCache.get(name)?.let { return it }
        val dir = imageDir ?: return null
        val f = java.io.File(dir, name)
        if (!f.exists()) return null
        val size = imageSizeOf(name) ?: return null
        val target = contentWidth().coerceAtLeast(1)
        var sample = 1
        while (size.first / (sample * 2) >= target && sample < 8) sample *= 2
        val opt = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath, opt) }.getOrNull() ?: return null
        bitmapCache.put(name, bmp)
        return bmp
    }

    /** 当前页的图后台解码，解好一张重绘一次 */
    private fun preloadImages(items: List<LayoutEngine.Item.Img>) {
        val sc = scope ?: return
        val need = items.map { it.name }.filter { bitmapCache.get(it) == null }.distinct()
        if (need.isEmpty()) return
        sc.launch {
            for (name in need) {
                val bmp = withContext(Dispatchers.IO) { decodeBitmap(name) } ?: continue
                if (bmp.isRecycled.not()) invalidate()
            }
        }
    }

    // ---------------- 触摸 ----------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downAt = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val dt = System.currentTimeMillis() - downAt
                if (abs(dx) < touchSlop && abs(dy) < touchSlop && dt < 450) {
                    when {
                        event.x < width * 0.28f -> prevPage()
                        event.x > width * 0.72f -> nextPage()
                        else -> listener?.onTapCenter()
                    }
                } else if (abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 1.4f) {
                    if (dx < 0) nextPage() else prevPage()
                }
                return true
            }
        }
        return true
    }

    // ---------------- 翻页过渡 ----------------

    /** 把当前这一页拍成位图，翻页时让它滑出去 */
    private fun captureGhost() {
        val wpx = width
        val hpx = height
        if (wpx <= 0 || hpx <= 0) return
        if (ghost == null || ghost!!.width != wpx || ghost!!.height != hpx) {
            ghost?.recycle()
            ghost = Bitmap.createBitmap(wpx, hpx, Bitmap.Config.RGB_565)
        }
        val bmp = ghost ?: return
        ghostAlpha = 0f
        ghostX = 0f
        draw(Canvas(bmp))
    }

    /**
     * 旧页朝翻页方向滑出并淡出，新页同时露出来。
     * 比纯淡入更有「翻过去」的方向感，170ms 不会拖慢连续按键。
     */
    private fun startFlip(forward: Boolean) {
        if (ghost == null) return
        ghostAnim?.cancel()
        ghostAlpha = 1f
        ghostX = 0f
        val dir = if (forward) -1f else 1f
        val w = width.toFloat().coerceAtLeast(1f)
        ghostAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 170
            addUpdateListener {
                val p = it.animatedValue as Float
                ghostX = dir * w * 0.55f * p
                ghostAlpha = 1f - p * 0.85f
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        ghostAnim?.cancel()
        layoutJob?.cancel()
        ghost?.recycle()
        ghost = null
        bitmapCache.evictAll()
        super.onDetachedFromWindow()
    }
}
