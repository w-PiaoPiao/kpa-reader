package com.kpa.reader.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kpa.reader.R
import com.kpa.reader.book.ChapterSplitter
import com.kpa.reader.data.Book
import com.kpa.reader.data.BookStore
import com.kpa.reader.data.Bookmark
import com.kpa.reader.data.Chapter
import com.kpa.reader.data.ReaderSettings
import com.kpa.reader.data.ReaderTheme
import com.kpa.reader.data.SettingsStore
import com.kpa.reader.doc.Document
import com.kpa.reader.input.Gamepad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * 阅读界面。
 *
 * 正文以 [Document]（纯文本 + 块序列）存在，进度始终是全书字符偏移，
 * 因此换字号、换主题、跳转、搜索命中都能落到同一个位置。
 */
class ReaderActivity : AppCompatActivity(), PageView.Listener {

    companion object {
        const val EXTRA_ID = "book_id"
        const val EXTRA_SEARCH = "search_query"
        fun intentOf(context: Context, bookId: String, searchQuery: String? = null): Intent {
            val i = Intent(context, ReaderActivity::class.java).putExtra(EXTRA_ID, bookId)
            if (!searchQuery.isNullOrBlank()) i.putExtra(EXTRA_SEARCH, searchQuery)
            return i
        }
    }

    private lateinit var pageView: PageView
    private lateinit var menuOverlay: View
    private lateinit var loading: ProgressBar
    private lateinit var tvBookTitle: TextView
    private lateinit var tvChapterTitle: TextView
    private lateinit var tvPercent: TextView
    private lateinit var btnTheme: TextView

    private lateinit var store: BookStore
    private lateinit var settingsStore: SettingsStore
    private var settings = ReaderSettings()

    private var book: Book? = null
    private var document: Document? = null
    private var chapters: List<Chapter> = emptyList()
    private var chapterIndex = 0
    private var imageDir: File? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastKeyAt = 0L
    private val lastActionAt = HashMap<Gamepad.Action, Long>()
    private var savedOffset = -1

    private lateinit var searchOverlay: View
    private lateinit var searchList: ListView
    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView
    private var searchResults: List<Hit> = emptyList()
    private var searchAdapter: ArrayAdapter<CharSequence>? = null

    private val saveRunnable = Runnable { saveProgressNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        store = BookStore.get(this)
        settingsStore = SettingsStore(this)
        settings = settingsStore.load()
        // 窗口底色跟随阅读主题，避免进入时先闪一下深色底再变纸白
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(settings.theme.bg)
        )

        pageView = findViewById(R.id.pageView)
        menuOverlay = findViewById(R.id.menuOverlay)
        loading = findViewById(R.id.loading)
        tvBookTitle = findViewById(R.id.tvBookTitle)
        tvChapterTitle = findViewById(R.id.tvChapterTitle)
        tvPercent = findViewById(R.id.tvPercent)
        btnTheme = findViewById(R.id.btnTheme)

        pageView.listener = this
        pageView.scope = lifecycleScope
        bindMenu()
        setupSearchUi()

        val id = intent.getStringExtra(EXTRA_ID)
        if (id.isNullOrEmpty()) { finish(); return }
        loadBook(id)
    }

    // ---------------- 菜单 ----------------

    private fun bindMenu() {
        findViewById<View>(R.id.menuScrim).setOnClickListener { setMenuVisible(false) }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnToc).setOnClickListener { showToc() }
        findViewById<View>(R.id.btnSearch).setOnClickListener { showSearch() }
        findViewById<View>(R.id.btnBookmark).setOnClickListener { addBookmark() }
        findViewById<View>(R.id.btnFontDown).setOnClickListener { changeFont(-1f) }
        findViewById<View>(R.id.btnFontUp).setOnClickListener { changeFont(1f) }
        findViewById<View>(R.id.btnTheme).setOnClickListener { cycleTheme() }
        findViewById<View>(R.id.btnNight).setOnClickListener { toggleNight() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        btnTheme.text = settings.theme.label
    }

    private fun setMenuVisible(visible: Boolean) {
        menuOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            menuOverlay.findViewById<View>(R.id.btnToc)?.requestFocus()
        } else {
            pageView.requestFocus()
        }
    }

    private fun toggleMenu() = setMenuVisible(menuOverlay.visibility != View.VISIBLE)

    // ---------------- 载入 ----------------

    private fun loadBook(id: String) {
        val b = store.find(id)
        if (b == null) { toast("书籍记录已丢失"); finish(); return }
        book = b
        showLoading(true)

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "books")
                val textFile = File(dir, "$id.txt")
                val blockFile = File(dir, "$id.blocks")
                val text = textFile.readText()
                val blocks = if (blockFile.exists()) {
                    Document.parseBlocks(blockFile.readText())
                } else {
                    Document.blocksFromPlainText(text)
                }
                Triple(text, blocks, File(dir, "$id.img"))
            }

            val doc = Document(loaded.first, loaded.second)
            document = doc
            imageDir = loaded.third
            chapters = if (b.chapters.isNotEmpty()) b.chapters else ChapterSplitter.split(doc.text)

            pageView.setDocument(doc, imageDir)
            pageView.configure(settings, b.title)

            if (b.chapters.size != chapters.size) {
                store.upsert(b.copy(chapters = chapters, charCount = doc.text.length))
            }

            showLoading(false)
            val offset = b.progressOffset.coerceIn(0, (doc.text.length - 1).coerceAtLeast(0))
            // 偏移为 0 时固定从第 0 章开始：封面这类纯图片章节没有字符区间，
            // 按偏移查找会被跳过，直接翻到后面的正文
            val idx = if (offset <= 0) 0 else ChapterSplitter.chapterIndexAt(chapters, offset)
            openChapter(idx, offset, record = false)
            updateHeader()

            val q = intent.getStringExtra(EXTRA_SEARCH)
            if (!q.isNullOrBlank()) showSearch(q)
        }
    }

    private fun showLoading(v: Boolean) { loading.visibility = if (v) View.VISIBLE else View.GONE }

    // ---------------- 章节 ----------------

    private fun openChapter(index: Int, absoluteChar: Int, record: Boolean = true) {
        val doc = document ?: return
        if (chapters.isEmpty() || doc.blocks.isEmpty()) return
        chapterIndex = index.coerceIn(0, chapters.size - 1)
        val ch = chapters[chapterIndex]

        val bStart: Int
        val bEnd: Int
        if (ch.blockStart in 0 until doc.blocks.size && ch.blockEnd > ch.blockStart) {
            bStart = ch.blockStart
            bEnd = ch.blockEnd.coerceAtMost(doc.blocks.size)
        } else {
            // 旧数据没有块区间，按字符偏移反推
            bStart = doc.blockIndexAtChar(ch.start).coerceIn(0, doc.blocks.size - 1)
            bEnd = (doc.blockIndexAtChar((ch.end - 1).coerceAtLeast(ch.start)) + 1)
                .coerceIn(bStart + 1, doc.blocks.size)
        }

        val target = absoluteChar.coerceIn(ch.start, maxOf(ch.start, ch.end - 1))

        pageView.configure(settings, book?.title ?: "")
        pageView.setChapter(bStart, bEnd, ch.title, target)
        pageView.overallPercent = progressOf(target)
        updateHeader()
        if (record) scheduleSave()
    }

    private fun progressOf(absoluteChar: Int): Float {
        val n = document?.text?.length ?: return 0f
        if (n <= 0) return 0f
        return (absoluteChar.toFloat() / n).coerceIn(0f, 1f)
    }

    private fun updateHeader() {
        val b = book ?: return
        tvBookTitle.text = b.title
        val ch = chapters.getOrNull(chapterIndex)
        tvChapterTitle.text = "${chapterIndex + 1}/${chapters.size} · ${ch?.title ?: ""}"
        tvPercent.text = "${(pageView.overallPercent * 100).toInt()}%"
    }

    // ---------------- 进度 ----------------

    private fun scheduleSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 700)
    }

    private fun saveProgressNow() {
        val b = book ?: return
        val doc = document ?: return
        if (doc.text.isEmpty() || chapters.isEmpty()) return
        val off = pageView.currentCharOffset().coerceIn(0, doc.text.length)
        if (off == savedOffset) return
        savedOffset = off
        store.updateProgress(b.id, off, off.toFloat() / doc.text.length)
        tvPercent.text = "${(pageView.overallPercent * 100).toInt()}%"
    }

    override fun onPause() {
        handler.removeCallbacks(saveRunnable)
        saveProgressNow()
        // 书库是异步写盘的，退出前等它落地，避免刚翻完页就切后台丢进度
        store.flush()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveRunnable)
        super.onDestroy()
    }

    // ---------------- PageView.Listener ----------------

    override fun onBoundary(forward: Boolean): Boolean {
        return if (forward) {
            if (chapterIndex + 1 < chapters.size) {
                openChapter(chapterIndex + 1, chapters[chapterIndex + 1].start)
                true
            } else {
                toast("已经是最后一章了")
                false
            }
        } else {
            if (chapterIndex - 1 >= 0) {
                val prev = chapters[chapterIndex - 1]
                openChapter(chapterIndex - 1, maxOf(prev.start, prev.end - 1))
                true
            } else {
                toast("已经是第一章了")
                false
            }
        }
    }

    override fun onTapCenter() = setMenuVisible(true)

    private val showLayoutLoading = Runnable { showLoading(true) }

    override fun onLayoutState(laying: Boolean) {
        handler.removeCallbacks(showLayoutLoading)
        if (laying) {
            // 分页通常几十毫秒，延迟 200ms 再显示转圈，避免每章都闪一下
            handler.postDelayed(showLayoutLoading, 200)
        } else {
            showLoading(false)
        }
    }

    override fun onPageChanged(page: Int, total: Int) {
        pageView.overallPercent = progressOf(pageView.currentCharOffset())
        scheduleSave()
        updateHeader()
    }

    // ---------------- 功能 ----------------

    private fun changeFont(delta: Float) {
        val next = settings.copy(fontSizeSp = (settings.fontSizeSp + delta).coerceIn(12f, 36f))
        if (next.fontSizeSp == settings.fontSizeSp) return
        settings = next
        settingsStore.save(settings)
        pageView.configure(settings, book?.title ?: "")
        updateHeader()
        toast("字号 ${settings.fontSizeSp.toInt()}")
    }

    private fun cycleTheme() {
        val list = ReaderTheme.entries
        applyTheme(list[(list.indexOf(settings.theme) + 1) % list.size])
    }

    private fun applyTheme(t: ReaderTheme) {
        settings = settings.copy(theme = t)
        settingsStore.save(settings)
        btnTheme.text = t.label
        pageView.configure(settings, book?.title ?: "")
    }

    private fun toggleNight() {
        settings = settingsStore.quickToggleNight(settings)
        settingsStore.save(settings)
        btnTheme.text = settings.theme.label
        pageView.configure(settings, book?.title ?: "")
        toast(if (settings.theme == ReaderTheme.NIGHT) "夜间模式" else "日间模式")
    }

    // ---------------- 目录 / 搜索 / 书签 ----------------

    private fun showToc() {
        if (chapters.isEmpty()) { toast("未识别到章节"); return }
        val labels = Array(chapters.size) { i ->
            val mark = if (i == chapterIndex) "▶ " else "　 "
            "$mark${i + 1}. ${chapters[i].title}"
        }
        showListDialog("目录（${chapters.size} 章）", labels, initialSelection = chapterIndex) { which ->
            openChapter(which, chapters[which].start)
            setMenuVisible(false)
        }
    }

    private class Hit(val offset: Int, val chapter: Int, val preview: String, val hitFrom: Int)

    private fun setupSearchUi() {
        searchOverlay = findViewById(R.id.searchOverlay)
        searchList = findViewById(R.id.searchList)
        searchInput = findViewById(R.id.searchInput)
        searchStatus = findViewById(R.id.searchStatus)

        val dm = resources.displayMetrics
        val rowH = (58 * dm.density).toInt()
        val ad = object : ArrayAdapter<CharSequence>(
            this, android.R.layout.simple_list_item_1, ArrayList<CharSequence>()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH
                )
                tv.minHeight = 0
                tv.gravity = Gravity.CENTER_VERTICAL
                tv.textSize = 12f
                tv.setTextColor(0xFFE2E7EE.toInt())
                return tv
            }
        }
        searchAdapter = ad
        searchList.adapter = ad
        searchList.setOnItemClickListener { _, _, pos, _ ->
            val hit = searchResults.getOrNull(pos) ?: return@setOnItemClickListener
            hideSearch()
            openChapter(hit.chapter, hit.offset)
        }

        searchInput.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                if (q.isNotEmpty()) runSearch(q)
            }
        })
        searchInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(searchInput.text.toString().trim())
                getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
                true
            } else false
        }

        // Activity 用了 decorFitsSystemWindows(false)（阅读时要全屏沉浸），
        // adjustResize 因此不生效，得自己按 IME inset 把搜索层底部抬起来。
        // 键盘在小横屏上要吃掉七成高度，列表根本放不下，所以键盘弹起时先只留输入框，
        // 按搜索键收起键盘后再展开结果列表
        val basePad = (12 * resources.displayMetrics.density).toInt()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(searchOverlay) { v, insets ->
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(basePad, basePad, basePad, basePad + ime)
            if (::searchList.isInitialized) {
                searchList.visibility = if (ime > 0) View.GONE else View.VISIBLE
            }
            insets
        }
    }

    private fun showSearch(initialQuery: String? = null) {
        searchOverlay.visibility = View.VISIBLE
        searchInput.requestFocus()
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        if (!initialQuery.isNullOrBlank()) {
            searchInput.setText(initialQuery)
            searchInput.setSelection(searchInput.text.length)
        }
    }

    private fun hideSearch() {
        searchOverlay.visibility = View.GONE
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        pageView.requestFocus()
    }

    private fun runSearch(q: String) {
        val doc = document ?: return
        val ad = searchAdapter ?: return
        if (q.isBlank()) {
            searchResults = emptyList()
            ad.clear(); ad.notifyDataSetChanged()
            searchStatus.text = ""
            return
        }
        searchStatus.text = "搜索中\u2026"
        lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) { searchAll(doc.text, q, 300) }
            if (searchOverlay.visibility != View.VISIBLE) return@launch
            searchResults = hits
            ad.clear()
            for (hit in hits) {
                val title = chapters.getOrNull(hit.chapter)?.title ?: ""
                val head = "$title \u27E9 "
                val ss = SpannableString(head + hit.preview)
                ss.setSpan(
                    ForegroundColorSpan(0xFFD9A441.toInt()),
                    0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                val hl = head.length + hit.hitFrom
                val hEnd = (hl + q.length).coerceAtMost(ss.length)
                if (hl in 0 until hEnd) {
                    ss.setSpan(
                        ForegroundColorSpan(0xFFE8623C.toInt()),
                        hl, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                ad.add(ss)
            }
            ad.notifyDataSetChanged()
            searchStatus.text = if (hits.isEmpty()) "\u6ca1\u6709\u627e\u5230\u300c$q\u300d"
            else "\u627e\u5230 ${hits.size} \u5904" + if (hits.size >= 300) "\uff08\u4ec5\u663e\u793a\u524d 300\uff09" else ""
        }
    }

    /** 全文检索：最多收集 [limit] 条命中 */
    private fun searchAll(text: String, rawQuery: String, limit: Int): List<Hit> {
        val q = rawQuery.trim()
        if (q.isEmpty()) return emptyList()
        // 纯中文查询不含大小写概念，用普通 indexOf 比 ignoreCase 快得多
        val needIgnoreCase = q.any { it.code in 0x41..0x5A || it.code in 0x61..0x7A }
        val out = ArrayList<Hit>(64)
        var from = 0
        while (out.size < limit) {
            val idx = if (needIgnoreCase) text.indexOf(q, from, ignoreCase = true)
            else text.indexOf(q, from)
            if (idx < 0) break
            val chIdx = ChapterSplitter.chapterIndexAt(chapters, idx)
            val s = (idx - 18).coerceAtLeast(0)
            val e = (idx + q.length + 26).coerceAtMost(text.length)
            val preview = text.substring(s, e).replace('\n', ' ').replace(Regex("\\s+"), " ")
            val lead = text.substring(s, idx).replace('\n', ' ').replace(Regex("\\s+"), " ").length
            out.add(Hit(idx, chIdx, preview, lead))
            from = idx + q.length
        }
        return out
    }

    private fun addBookmark() {
        val b = book ?: return
        val doc = document ?: return
        val off = pageView.currentCharOffset()
        // 同一屏反复按 Y 不该堆出一串书签
        if (b.bookmarks.any { kotlin.math.abs(it.offset - off) < 30 }) {
            toast("这一页已经有书签了")
            return
        }
        val preview = doc.text.substring(off.coerceIn(0, doc.text.length),
            (off + 24).coerceIn(0, doc.text.length)).replace("\n", " ").trim()
        val bm = Bookmark(off, preview, System.currentTimeMillis())
        store.upsert(b.copy(bookmarks = b.bookmarks + bm))
        book = store.find(b.id)
        toast("已加书签")
    }

    private fun showBookmarks() {
        val b = book ?: return
        if (b.bookmarks.isEmpty()) { toast("还没有书签"); return }
        val marks = b.bookmarks
        val labels = marks.map { it.preview.ifEmpty { "（空）" } }.toTypedArray()
        showListDialog("书签（${marks.size}）", labels, "全部清除", {
            book?.let { cur ->
                store.upsert(cur.copy(bookmarks = emptyList()))
                book = store.find(cur.id)
            }
            toast("已清空书签")
        }) { which ->
            val off = marks[which].offset
            val idx = ChapterSplitter.chapterIndexAt(chapters, off)
            openChapter(idx, off)
            setMenuVisible(false)
        }
    }

    private fun showSettings() {
        setMenuVisible(false)
        val dm = resources.displayMetrics
        val d = dm.density
        val pad = (16 * d).toInt()

        // 横屏宽度够，排成两列：左列排版参数、右列开关与跳转，
        // 单列的话 640 高放不下全部设置项，最下面两项会被裁掉
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), 0, 0, 0)
        }
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        fun title(parent: LinearLayout, t: String) {
            parent.addView(TextView(this).apply {
                text = t
                setTextColor(0xFFD9A441.toInt())
                textSize = 12f
                setPadding(0, (10 * d).toInt(), 0, (6 * d).toInt())
            })
        }

        fun slider(parent: LinearLayout, label: String, min: Int, max: Int, value: Int,
                   suffix: String, onCommit: (Int) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tv = TextView(this).apply {
                text = "$label $value$suffix"
                setTextColor(0xFFE2E7EE.toInt())
                textSize = 12f
            }
            val sb = SeekBar(this).apply {
                this.max = (max - min).coerceAtLeast(1)
                progress = (value - min).coerceIn(0, max - min)
            }
            var latest = value
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    latest = p + min
                    tv.text = "$label $latest$suffix"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                // 只在松手时应用：拖动过程中反复重排会很卡
                override fun onStopTrackingTouch(s: SeekBar?) { onCommit(latest) }
            })
            row.addView(tv, LinearLayout.LayoutParams((96 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(sb, LinearLayout.LayoutParams(0, (30 * d).toInt(), 1f))
            parent.addView(row)
        }

        fun toggle(parent: LinearLayout, label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
            var state = initial
            val tv = TextView(this).apply {
                text = "$label：" + if (state) "开" else "关"
                setTextColor(0xFFE2E7EE.toInt())
                textSize = 12f
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                isClickable = true
                setOnClickListener {
                    state = !state
                    text = "$label：" + if (state) "开" else "关"
                    onChange(state)
                }
            }
            parent.addView(tv)
        }

        title(left, "排版")
        slider(left, "字号", 12, 36, settings.fontSizeSp.roundToInt(), "sp") { v ->
            applySettings(settings.copy(fontSizeSp = v.toFloat()))
        }
        slider(left, "行距", 85, 200, (settings.lineSpacingMult * 100).roundToInt(), "%") { v ->
            applySettings(settings.copy(lineSpacingMult = v / 100f))
        }
        slider(left, "左右边距", 4, 40, settings.padHdp, "dp") { v ->
            applySettings(settings.copy(padHdp = v))
        }
        slider(left, "上下边距", 4, 32, settings.padVdp, "dp") { v ->
            applySettings(settings.copy(padVdp = v))
        }

        title(right, "显示")
        toggle(right, "衬线字体", settings.serif) { on -> applySettings(settings.copy(serif = on)) }
        toggle(right, "页眉页脚", settings.showStatusBar) { on -> applySettings(settings.copy(showStatusBar = on)) }
        toggle(right, "按句分段", settings.sentenceSegments) { on ->
            applySettings(settings.copy(sentenceSegments = on))
        }

        title(right, "跳转")
        val jumpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val jumpTv = TextView(this).apply {
            text = "${(pageView.overallPercent * 100).toInt()}%"
            setTextColor(0xFFE2E7EE.toInt())
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
        }
        val jumpSb = SeekBar(this).apply {
            max = 1000
            progress = (pageView.overallPercent * 1000).toInt().coerceIn(0, 1000)
        }
        jumpSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { jumpTv.text = "${p / 10}%" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        jumpRow.addView(jumpTv, LinearLayout.LayoutParams((56 * d).toInt(), (30 * d).toInt()))
        jumpRow.addView(jumpSb, LinearLayout.LayoutParams(0, (30 * d).toInt(), 1f))
        right.addView(jumpRow)

        val dlg = Dialog(this)
        barButton("跳到该位置") {
            val doc = document
            if (doc != null && doc.text.isNotEmpty()) {
                val off = (doc.text.length.toLong() * jumpSb.progress / 1000).toInt()
                    .coerceIn(0, doc.text.length - 1)
                val idx = ChapterSplitter.chapterIndexAt(chapters, off)
                openChapter(idx, off)
            }
            dlg.dismiss()
        }.also { jumpBtn ->
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setBackgroundColor(0xFF1B2129.toInt())
                addView(jumpBtn)
                addView(barButton("关闭") { dlg.dismiss() })
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF1B2129.toInt())
            }
            container.addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            container.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * d).toInt()))
            dlg.setContentView(container)
            dlg.window?.setLayout((dm.widthPixels * 0.86f).toInt(), (dm.heightPixels * 0.7f).toInt())
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(0xFF1B2129.toInt())
            })
            dlg.show()
        }
    }

    private fun applySettings(s: ReaderSettings) {
        settings = s
        settingsStore.save(s)
        btnTheme.text = s.theme.label
        pageView.configure(s, book?.title ?: "")
        updateHeader()
    }

    // ---------------- 通用列表弹窗 ----------------

    private fun showListDialog(
        title: String,
        items: Array<String>,
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
        initialSelection: Int = -1,
        onPick: (Int) -> Unit
    ) {
        val dm = resources.displayMetrics
        val pad = (16 * dm.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1B2129.toInt())
            setPadding(pad, pad, pad, 0)
        }
        root.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFE8E4DA.toInt())
            textSize = 15f
            setPadding(0, 0, 0, (pad * 0.6f).toInt())
        })

        val rowH = (38 * dm.density).toInt()
        val listAdapter = object : ArrayAdapter<String>(
            this@ReaderActivity, android.R.layout.simple_list_item_1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH
                )
                tv.minHeight = 0
                tv.gravity = Gravity.CENTER_VERTICAL
                tv.textSize = 13f
                tv.setTextColor(0xFFE2E7EE.toInt())
                return tv
            }
        }
        val list = ListView(this).apply {
            adapter = listAdapter
            divider = ColorDrawable(0xFF2A3038.toInt())
            dividerHeight = 1
            isFocusable = true
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val dlg = Dialog(this)
        if (neutralText != null) {
            bar.addView(barButton(neutralText) { onNeutral?.invoke() })
        }
        bar.addView(barButton("关闭") { dlg.dismiss() })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dm.density).toInt()))

        dlg.setContentView(root)
        dlg.window?.setLayout((dm.widthPixels * 0.66f).toInt(), (dm.heightPixels * 0.92f).toInt())
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12 * dm.density
            setColor(0xFF1B2129.toInt())
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            onPick(pos)
            dlg.dismiss()
        }
        dlg.show()
        if (initialSelection >= 0) {
            // 目录打开时直接定位到当前章，几百章的书不用手动翻
            list.setSelection((initialSelection - 2).coerceAtLeast(0))
        }
    }

    private fun barButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFFDCE3EC.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            val d = resources.displayMetrics.density
            setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ---------------- 按键 ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 搜索层打开时，B/BACK 收起；其余按键交给系统处理列表导航与文字输入
        if (::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
            ) {
                hideSearch()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (menuOverlay.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                        (currentFocus ?: menuOverlay.findViewById(R.id.btnToc))?.performClick()
                        return true
                    }
                    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_BUTTON_START -> {
                        setMenuVisible(false)
                        return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        val action = Gamepad.actionOf(event.keyCode) ?: return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            if (event.repeatCount > 0) {
                if (!Gamepad.isRepeatable(action)) return true
                if (now - lastKeyAt < Gamepad.REPEAT_INTERVAL_MS) return true
            } else if (!Gamepad.isRepeatable(action)) {
                if (now - (lastActionAt[action] ?: 0L) < 260L) return true
                lastActionAt[action] = now
            }
            lastKeyAt = now
            if (handleAction(action)) return true
        }
        if (event.action == KeyEvent.ACTION_UP) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleAction(a: Gamepad.Action): Boolean {
        when (a) {
            Gamepad.Action.PREV_PAGE -> pageView.prevPage()
            Gamepad.Action.NEXT_PAGE -> pageView.nextPage()
            Gamepad.Action.PREV_CHAPTER -> openChapter(chapterIndex - 1, chapters.getOrElse(chapterIndex - 1) { chapters.first() }.start)
            Gamepad.Action.NEXT_CHAPTER -> openChapter(chapterIndex + 1, chapters.getOrElse(chapterIndex + 1) { chapters.last() }.start)
            Gamepad.Action.TOGGLE_TOC -> showToc()
            Gamepad.Action.FIND -> showSearch()
            Gamepad.Action.ADD_BOOKMARK -> addBookmark()
            Gamepad.Action.TOGGLE_MENU -> toggleMenu()
            Gamepad.Action.TOGGLE_NIGHT -> toggleNight()
            Gamepad.Action.FONT_DOWN -> changeFont(-1f)
            Gamepad.Action.FONT_UP -> changeFont(1f)
            Gamepad.Action.BACK -> {
                if (menuOverlay.visibility == View.VISIBLE) setMenuVisible(false) else finish()
            }
        }
        return true
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
