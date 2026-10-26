package com.kpa.reader.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.BatteryManager
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
import android.widget.ScrollView
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
import com.kpa.reader.doc.Block
import com.kpa.reader.doc.Document
import com.kpa.reader.input.Gamepad
import com.kpa.reader.input.Keymap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
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

        /** 后退双击验证的时间窗：2 秒内再按一次才退回书架 */
        private const val EXIT_CONFIRM_MS = 2000L

        /** 预览文本压空白用：searchAll 每条命中都要跑，不能在循环里反复编译 */
        private val WS_REGEX = Regex("\\s+")

        /** 章节名自带编号的两种常见写法：「第 2 节」「1. 序」（章号与量词之间常带空格） */
        private val NUMBERED_HEADING = Regex("^第\\s*[0-9零一二三四五六七八九十百千两]+\\s*[章节卷部回篇]")
        private val PLAIN_INDEX = Regex("^\\d+[.、\\s]")

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
    private lateinit var tvFirstHint: TextView

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
    private var lastBackPressAt = 0L
    private var lastClearBookmarksAt = 0L
    private var savedOffset = -1
    /** 与 [savedOffset] 配套：最后落盘时的全书百分比，用于落盘频率阈值判断 */
    private var savedPct = -1f

    /** 页脚电量；-1 未知。阅读页屏幕常亮又没有系统状态栏，电量只有这里能看 */
    private var batteryPct = -1
    private var lowBatteryToasted = false

    /** 菜单逻辑状态：关闭淡出期间 visibility 仍是 VISIBLE，按键路由不能看它 */
    private var menuOpen = false

    private val batteryTick = Runnable { refreshBattery(reschedule = true) }

    private lateinit var searchOverlay: View
    private lateinit var searchList: ListView
    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView
    private var searchResults: List<Hit> = emptyList()
    private var searchAdapter: ArrayAdapter<CharSequence>? = null
    /** 当前搜索任务：边打边搜时先取消上一个，防止慢的旧词结果回来覆盖新词结果 */
    private var searchJob: kotlinx.coroutines.Job? = null

    /** 边打边搜的 250ms 防抖，统一持有才能在收起搜索层时取消 */
    private val searchDebounceRunnable = Runnable {
        if (::searchInput.isInitialized) runSearch(searchInput.text.toString().trim())
    }

    private val saveRunnable = Runnable { saveProgressNow() }
    /** loadBook 在 IO 线程产出的全部结果（文本、块、图片目录、章节） */
    private class LoadedBook(
        val text: String,
        val blocks: List<Block>,
        val imgDir: File,
        val chapters: List<Chapter>
    )

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
        Keymap.init(this)
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
        tvFirstHint = findViewById(R.id.tvFirstHint)

        pageView.listener = this
        pageView.scope = lifecycleScope
        // 提前注入用户设置：loading 转圈期间 PageView 画的底色就是当前主题，
        // 否则要等 loadBook 完成才 configure，非纸白主题会先闪一片默认纸白
        pageView.configure(settings)
        bindMenu()
        setupSearchUi()
        refreshBattery(reschedule = true)

        val id = intent.getStringExtra(EXTRA_ID)
        if (id.isNullOrEmpty()) { finish(); return }
        loadBook(id)
    }

    // ---------------- 菜单 ----------------

    private fun bindMenu() {
        findViewById<View>(R.id.menuScrim).setOnClickListener { setMenuVisible(false) }
        // 和 B 键同走双击确认：菜单按钮是显式操作，但误触一下就丢阅读位置同样难受
        findViewById<View>(R.id.btnBack).setOnClickListener { requestExit("再按一次「返回」退出阅读") }
        findViewById<View>(R.id.btnToc).setOnClickListener { showToc() }
        findViewById<View>(R.id.btnSearch).setOnClickListener { showSearch() }
        findViewById<View>(R.id.btnBookmark).setOnClickListener { addBookmark() }
        findViewById<View>(R.id.btnBookmarks).setOnClickListener { showBookmarks() }
        findViewById<View>(R.id.btnTheme).setOnClickListener { cycleTheme() }
        findViewById<View>(R.id.btnNight).setOnClickListener { toggleNight() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        btnTheme.text = settings.theme.label
    }

    private fun setMenuVisible(visible: Boolean) {
        menuOverlay.animate().cancel()
        menuOverlay.alpha = 1f
        if (visible) {
            // 打开时 120ms 淡入只做 alpha，焦点立即给：动画期间按键就要能操作。
            menuOpen = true
            menuOverlay.alpha = 0f
            menuOverlay.visibility = View.VISIBLE
            menuOverlay.animate().alpha(1f).setDuration(120L).start()
            // 阅读区在 touch mode 下会一直占着焦点，菜单打开时必须让出，
            // 否则十字键的导航起点还在 PageView 上，菜单按钮选不中
            pageView.isFocusable = false
            pageView.isFocusableInTouchMode = false
            menuOverlay.findViewById<View>(R.id.btnToc)?.requestFocus()
        } else {
            // 焦点与按键路由先切干净，再让浮层淡出：
            // 淡出这 90ms 里按键已经回到阅读页，不会误触菜单按钮
            menuOpen = false
            pageView.isFocusable = true
            pageView.isFocusableInTouchMode = true
            pageView.requestFocus()
            menuOverlay.animate().alpha(0f).setDuration(90L).withEndAction {
                menuOverlay.visibility = View.GONE
                menuOverlay.alpha = 1f
            }.start()
        }
    }

    private fun toggleMenu() = setMenuVisible(!menuOpen)

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
                // 旧书库可能没存章节记录：全书扫描 77 万字要上百毫秒，一并留在 IO 线程
                val chapters = if (b.chapters.isNotEmpty()) b.chapters else ChapterSplitter.split(text)
                LoadedBook(text, blocks, File(dir, "$id.img"), chapters)
            }

            val doc = Document(loaded.text, loaded.blocks)
            document = doc
            imageDir = loaded.imgDir
            chapters = loaded.chapters

            pageView.setDocument(doc, imageDir)
            pageView.configure(settings)

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
            maybeShowFirstRunHint()
        }
    }

    private fun showLoading(v: Boolean) { loading.visibility = if (v) View.VISIBLE else View.GONE }

    // ---------------- 章节 ----------------

    private fun openChapter(
        index: Int,
        absoluteChar: Int,
        record: Boolean = true,
        lastPage: Boolean = false
    ) {
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

        pageView.configure(settings)
        pageView.setChapter(bStart, bEnd, ch.title, target, lastPage)
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
        // 每次翻页都会走到：值没变就别 setText，省一轮无谓的 requestLayout
        if (tvBookTitle.text != b.title) tvBookTitle.text = b.title
        val ch = chapters.getOrNull(chapterIndex)
        val chTitle = "${chapterIndex + 1}/${chapters.size} · ${ch?.title ?: ""}"
        if (tvChapterTitle.text != chTitle) tvChapterTitle.text = chTitle
        val pct = "${(pageView.overallPercent * 100).toInt()}%"
        if (tvPercent.text != pct) tvPercent.text = pct
    }

    // ---------------- 进度 ----------------

    private fun scheduleSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 700)
    }

    /**
     * 持续阅读时避免高频全量 JSON 落盘：进度变化不足 0.1% 且不足 2000 字先不写
     * （书多时一次 persist 是几百 KB 的序列化 + 写盘，费电也磨闪存）。
     * [force] 给 onPause 兜底用，退出前必落；接近读完时放开阈值，别错过 finished 标记。
     * 中途被杀最多丢 0.1% 书长的位置，约半页。
     */
    private fun saveProgressNow(force: Boolean = false) {
        val b = book ?: return
        val doc = document ?: return
        if (doc.text.isEmpty() || chapters.isEmpty()) return
        val off = pageView.currentCharOffset().coerceIn(0, doc.text.length)
        if (off == savedOffset) return
        val pct = off.toFloat() / doc.text.length
        if (!force && abs(pct - savedPct) < 0.001f &&
            abs(off - savedOffset) < 2000 && pct < 0.995f
        ) return
        savedOffset = off
        savedPct = pct
        store.updateProgress(b.id, off, pct)
        tvPercent.text = "${(pageView.overallPercent * 100).toInt()}%"
    }

    override fun onPause() {
        handler.removeCallbacks(saveRunnable)
        handler.removeCallbacks(batteryTick)
        handler.removeCallbacks(hideFirstHint)
        handler.removeCallbacks(fontToastRun)
        saveProgressNow(force = true)
        // 书库是异步写盘的，退出前等它落地，避免刚翻完页就切后台丢进度
        store.flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshBattery(reschedule = true)
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveRunnable)
        handler.removeCallbacks(batteryTick)
        handler.removeCallbacks(hideFirstHint)
        handler.removeCallbacks(fontToastRun)
        super.onDestroy()
    }

    // ---------------- 电量 / 首次提示 ----------------

    /**
     * 查一次电量并刷到页脚。电量是分钟级变化的低频信息，60s 轮询足够；
     * 跌破 15% 只 toast 一次，回升到 25% 以上重置，下次跌破还会提醒。
     */
    private fun refreshBattery(reschedule: Boolean) {
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        if (pct != batteryPct) {
            batteryPct = pct
            pageView.batteryPct = pct
            pageView.invalidate()
            if (pct in 1..15 && !lowBatteryToasted) {
                lowBatteryToasted = true
                toast("电量只剩 $pct%，记得充电")
            } else if (pct > 25) {
                lowBatteryToasted = false
            }
        }
        if (reschedule) handler.postDelayed(batteryTick, 60_000L)
    }

    /**
     * 第一次进阅读界面的按键提示。只出现一次（SP 标记），任意按键或 4 秒后消失，
     * 不打断操作 —— 第一次按键既关提示又照常执行动作。
     */
    private fun maybeShowFirstRunHint() {
        val sp = getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
        if (sp.getBoolean("first_hint_shown", false)) return
        sp.edit().putBoolean("first_hint_shown", true).apply()
        // 键名按当前映射动态取，用户改过键提示也不会说谎
        val nextKey = Keymap.name(Keymap.codeOf(Gamepad.Action.NEXT_PAGE))
        val menuKey = Keymap.name(Keymap.codeOf(Gamepad.Action.TOGGLE_MENU))
        tvFirstHint.text = "十字键 翻页　·　$nextKey 下一页\n$menuKey 菜单　·　B 连按两次返回书架"
        // 出现淡入对齐消失的淡出，避免同一条提示一硬切一渐隐
        tvFirstHint.animate().cancel()
        tvFirstHint.alpha = 0f
        tvFirstHint.visibility = View.VISIBLE
        tvFirstHint.animate().alpha(1f).setDuration(200L).start()
        handler.postDelayed(hideFirstHint, 4000L)
    }

    private val hideFirstHint = Runnable {
        tvFirstHint.animate().alpha(0f).setDuration(400L)
            .withEndAction { tvFirstHint.visibility = View.GONE }.start()
    }

    private fun dismissFirstHint() {
        handler.removeCallbacks(hideFirstHint)
        if (tvFirstHint.visibility == View.VISIBLE) {
            tvFirstHint.animate().cancel()
            tvFirstHint.alpha = 0f
            tvFirstHint.visibility = View.GONE
        }
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
                // 直接落到上一章的最后一页；用 prev.end-1 当起点只会排出近乎空白的一页
                openChapter(
                    chapterIndex - 1,
                    maxOf(prev.start, prev.end - 1),
                    lastPage = true
                )
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

    private fun changeFont(delta: Float, fromRepeat: Boolean = false) {
        val next = settings.copy(fontSizeSp = (settings.fontSizeSp + delta).coerceIn(12f, 36f))
        if (next.fontSizeSp == settings.fontSizeSp) return
        settings = next
        settingsStore.save(settings)
        pageView.configure(settings)
        updateHeader()
        showFontToast(fromRepeat)
    }

    /** 连发时 80ms 一次 toast 会被反复重置成常驻闪字；去抖 350ms，停手后弹一次最终字号 */
    private val fontToastRun = Runnable { toast("字号 ${settings.fontSizeSp.toInt()}") }

    private fun showFontToast(fromRepeat: Boolean) {
        handler.removeCallbacks(fontToastRun)
        if (fromRepeat) {
            handler.postDelayed(fontToastRun, 350L)
        } else {
            toast("字号 ${settings.fontSizeSp.toInt()}")
        }
    }

    private fun cycleTheme() {
        val list = ReaderTheme.entries
        applyTheme(list[(list.indexOf(settings.theme) + 1) % list.size])
    }

    private fun applyTheme(t: ReaderTheme) {
        settings = settings.copy(theme = t)
        settingsStore.save(settings)
        btnTheme.text = t.label
        pageView.configure(settings)
    }

    private fun toggleNight() {
        settings = settingsStore.quickToggleNight(settings)
        settingsStore.save(settings)
        btnTheme.text = settings.theme.label
        pageView.configure(settings)
        toast(if (settings.theme == ReaderTheme.NIGHT) "夜间模式" else "日间模式")
    }

    // ---------------- 目录 / 搜索 / 书签 ----------------

    private fun showToc() {
        if (chapters.isEmpty()) { toast("未识别到章节"); return }
        val labels = Array(chapters.size) { i ->
            val mark = if (i == chapterIndex) "▶ " else "　 "
            // 章节名自带「第 2 节」「第三章」时不再补序号，避免「2. 第 2 节」
            val title = chapters[i].title
            val numbered = NUMBERED_HEADING.containsMatchIn(title) || PLAIN_INDEX.containsMatchIn(title)
            "$mark" + if (numbered) title else "${i + 1}. $title"
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
                tv.setTextColor(getColor(R.color.text_secondary))
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
                handler.removeCallbacks(searchDebounceRunnable)
                if (q.isNotEmpty()) handler.postDelayed(searchDebounceRunnable, 250)
            }
        })
        searchInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacks(searchDebounceRunnable)
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
        searchOverlay.animate().cancel()
        searchOverlay.alpha = 0f
        searchOverlay.visibility = View.VISIBLE
        searchOverlay.animate().alpha(1f).setDuration(120L).start()
        searchInput.requestFocus()
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        if (!initialQuery.isNullOrBlank()) {
            searchInput.setText(initialQuery)
            searchInput.setSelection(searchInput.text.length)
        }
    }

    private fun hideSearch() {
        searchOverlay.animate().cancel()
        searchOverlay.alpha = 1f
        handler.removeCallbacks(searchDebounceRunnable)
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
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) { searchAll(doc.text, q, 300) }
            if (searchOverlay.visibility != View.VISIBLE) return@launch
            searchResults = hits
            ad.clear()
            for (hit in hits) {
                val title = chapters.getOrNull(hit.chapter)?.title ?: ""
                val head = "$title \u27E9 "
                val ss = SpannableString(head + hit.preview)
                // 章节前缀降到 tertiary：一屏十几个金色前缀会把真正要看的命中词淹掉
                ss.setSpan(
                    ForegroundColorSpan(getColor(R.color.text_tertiary)),
                    0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                val hl = head.length + hit.hitFrom
                val hEnd = (hl + q.length).coerceAtMost(ss.length)
                if (hl in 0 until hEnd) {
                    ss.setSpan(
                        ForegroundColorSpan(getColor(R.color.highlight)),
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
            val preview = text.substring(s, e).replace('\n', ' ').replace(WS_REGEX, " ")
            val lead = text.substring(s, idx).replace('\n', ' ').replace(WS_REGEX, " ").length
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
        showListDialog("书签（${marks.size}）", labels, "全部清除", { dlg ->
            // 手柄的焦点从列表按到底很容易滑到「全部清除」上，误按一次就全没了。
            // 所以第一次只提示，2.5 秒内再按一次才真的清空
            val now = SystemClock.uptimeMillis()
            if (now - lastClearBookmarksAt > 2500L) {
                lastClearBookmarksAt = now
                toast("再按一次「全部清除」确认")
            } else {
                lastClearBookmarksAt = 0L
                book?.let { cur ->
                    store.upsert(cur.copy(bookmarks = emptyList()))
                    book = store.find(cur.id)
                }
                toast("已清空书签")
                // 必须关掉弹窗：列表数据是打开时的快照，不清的话看起来像「没清掉」，
                // 点旧条目还会按已失效的偏移跳转
                dlg.dismiss()
            }
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

        // 单列排布：手柄的左右键要留给滑块调值、开关切换，
        // 一旦分成两列，右列就永远到不了（左右键被滑块吃掉了）。
        // 高度靠 ScrollView 兜底，字号调大也不会把最后几项裁掉。
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val left = root
        val right = root

        fun title(parent: LinearLayout, t: String) {
            parent.addView(TextView(this).apply {
                text = t
                setTextColor(getColor(R.color.accent))
                textSize = 13f
                setPadding(0, (14 * d).toInt(), 0, (6 * d).toInt())
            })
        }

        // 记住第一个可聚焦控件，面板弹出时把焦点给它，手柄一进来就能直接调
        var firstFocusable: View? = null

        /**
         * 「左标签 + 右状态」行：开关、主题、按键折叠共用一套视觉，
         * 状态色只在有值/可展开时用金色，关闭态降到 faint，一眼看出当前状态。
         */
        fun actionRow(parent: LinearLayout, label: String, state: String,
                      onActivate: (TextView) -> Unit): Pair<View, TextView> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * d).toInt(), (5 * d).toInt(), (8 * d).toInt(), (5 * d).toInt())
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val stateTv = TextView(this).apply {
                text = state
                setTextColor(getColor(R.color.accent))
                textSize = 12f
            }
            row.addView(stateTv)
            row.setOnClickListener { onActivate(stateTv) }
            row.setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(if (hasFocus) getColor(R.color.accent_focus_bg) else 0x00000000)
            }
            // 手柄 A 在系统里不是 ENTER，Dialog 里也没人帮忙转成点击，得自己处理
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        row.performClick(); true
                    }
                    else -> false
                }
            }
            parent.addView(row)
            return row to stateTv
        }

        fun slider(parent: LinearLayout, label: String, min: Int, max: Int, value: Int,
                   suffix: String, onCommit: (Int) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tv = TextView(this).apply {
                text = "$label $value$suffix"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            }
            val sb = SeekBar(this).apply {
                this.max = (max - min).coerceAtLeast(1)
                progress = (value - min).coerceIn(0, max - min)
                isFocusable = true
                isFocusableInTouchMode = true
                // 不设 tint 会吃 Material3 默认的紫色：滑块是全 app 唯一的原生控件色，
                // 和金色体系差得最远，这里按 accent/line 定档
                progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
                progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.line))
                thumbTintList = ColorStateList.valueOf(getColor(R.color.accent))
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
            // 手柄左右键：SeekBar 自己会挪进度，但**不会**回调 onStopTrackingTouch，
            // 不在这里提交的话只有标签在变、设置其实没生效。
            // 但不能每按一下就提交 —— 一次提交就是一整章重排，长按把行距从 85% 调到
            // 200% 会连排二十多次。所以按住时只更新标签，300ms 内的连按合并成最后一次提交。
            var pendingCommit: Runnable? = null
            sb.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val step = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> return@setOnKeyListener false
                }
                val np = (sb.progress + step).coerceIn(0, sb.max)
                if (np == sb.progress) return@setOnKeyListener true
                sb.progress = np
                pendingCommit?.let { handler.removeCallbacks(it) }
                val commit = Runnable { onCommit(np + min) }
                pendingCommit = commit
                handler.postDelayed(commit, 300)
                true
            }
            // 焦点底给整行：只包 SeekBar 时会在行中间浮出一块色带，和开关行对不齐
            sb.setOnFocusChangeListener { _, hasFocus ->
                row.setBackgroundColor(if (hasFocus) getColor(R.color.accent_focus_bg) else 0x00000000)
            }
            if (firstFocusable == null) firstFocusable = sb
            row.addView(tv, LinearLayout.LayoutParams((96 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(sb, LinearLayout.LayoutParams(0, (30 * d).toInt(), 1f))
            parent.addView(row)
        }

        fun toggle(parent: LinearLayout, label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
            var state = initial
            val (_, stateTv) = actionRow(parent, label, if (state) "开" else "关") { tv ->
                state = !state
                tv.text = if (state) "开" else "关"
                tv.setTextColor(getColor(if (state) R.color.accent else R.color.text_faint))
                onChange(state)
            }
            if (!state) stateTv.setTextColor(getColor(R.color.text_faint))
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
        toggle(right, "音量键翻页", settings.volumeKeyTurn) { on ->
            applySettings(settings.copy(volumeKeyTurn = on))
        }
        // 主题循环原先只藏在菜单的「纸白」按钮里，用户根本不知道那个按钮能点
        actionRow(right, "阅读主题", settings.theme.label) { tv ->
            val list = ReaderTheme.entries
            applyTheme(list[(list.indexOf(settings.theme) + 1) % list.size])
            tv.text = settings.theme.label
        }

        title(right, "跳转")
        val jumpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val jumpTv = TextView(this).apply {
            text = "${(pageView.overallPercent * 100).toInt()}%"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
        }
        val jumpSb = SeekBar(this).apply {
            max = 1000
            progress = (pageView.overallPercent * 1000).toInt().coerceIn(0, 1000)
            isFocusable = true
            isFocusableInTouchMode = true
            progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
            progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.line))
            thumbTintList = ColorStateList.valueOf(getColor(R.color.accent))
            setOnFocusChangeListener { _, hasFocus ->
                jumpRow.setBackgroundColor(if (hasFocus) getColor(R.color.accent_focus_bg) else 0x00000000)
            }
        }
        jumpSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { jumpTv.text = "${p / 10}%" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        jumpRow.addView(jumpTv, LinearLayout.LayoutParams((56 * d).toInt(), (30 * d).toInt()))
        jumpRow.addView(jumpSb, LinearLayout.LayoutParams(0, (30 * d).toInt(), 1f))
        right.addView(jumpRow)

        // ---- 按键（默认收起：10 行映射常驻会把设置面板撑成两屏）----
        title(right, "按键")
        val keyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        var keysExpanded = false
        val keyRows = LinkedHashMap<Gamepad.Action, TextView>()
        var capturing: Gamepad.Action? = null

        fun refreshKeyRows() {
            keyRows.forEach { (a, tv) ->
                tv.text = if (capturing == a) {
                    "${Keymap.label(a)}　—　按任意键…"
                } else {
                    "${Keymap.label(a)}　—　${Keymap.name(Keymap.codeOf(a))}"
                }
            }
        }

        fun keyRow(action: Gamepad.Action) {
            val tv = TextView(this).apply {
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding((8 * d).toInt(), (5 * d).toInt(), (8 * d).toInt(), (5 * d).toInt())
                isFocusable = true
                isFocusableInTouchMode = true
                setOnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(if (hasFocus) getColor(R.color.accent_focus_bg) else 0x00000000)
                }
                setOnClickListener {
                    capturing = action
                    refreshKeyRows()
                    toast("按下要绑定的键，B 取消")
                }
            }
            keyBox.addView(tv)
            keyRows[action] = tv
        }

        for (action in Keymap.BINDABLE) keyRow(action)
        refreshKeyRows()

        var keyToggleRow: View? = null
        val (toggleRow, _) = actionRow(right, "按键映射", "展开") { tv ->
            keysExpanded = !keysExpanded
            keyBox.visibility = if (keysExpanded) View.VISIBLE else View.GONE
            tv.text = if (keysExpanded) "收起" else "展开"
            if (!keysExpanded) keyToggleRow?.requestFocus()
        }
        keyToggleRow = toggleRow
        keyBox.addView(TextView(this).apply {
            text = "方向键选中后按 A 改绑，B 取消"
            setTextColor(getColor(R.color.text_tertiary))
            textSize = 10.5f
            setPadding((8 * d).toInt(), 0, 0, (4 * d).toInt())
        }, 0)
        right.addView(keyBox)

        val dlg = Dialog(this)
        // Dialog 有独立的 window，Activity 的按键逻辑进不来：
        // 手柄 A 不是 ENTER，所以要在这里把它转成「点击当前焦点控件」
        dlg.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            // 捕获模式优先：此时按下的键是要绑定的目标，不触发控件点击
            val cap = capturing
            if (cap != null) {
                when {
                    keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B -> {
                        capturing = null
                        refreshKeyRows()
                        toast("已取消")
                    }
                    Keymap.canBind(keyCode) -> {
                        Keymap.bind(cap, keyCode)
                        capturing = null
                        refreshKeyRows()
                        toast("${Keymap.label(cap)} → ${Keymap.name(keyCode)}")
                    }
                    // 按了十字键这类不可绑的键：不提示的话行会一直停在「按任意键…」，
                    // 用户不知道为什么没反应
                    else -> toast("十字键和 B 是基础键，不能改绑")
                }
                return@setOnKeyListener true
            }

            when (keyCode) {
                // B 关闭面板：书库弹窗、菜单层都是「B = 关闭」，设置页不能例外
                KeyEvent.KEYCODE_BUTTON_B -> { dlg.dismiss(); true }
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    val focused = dlg.window?.decorView?.findFocus()
                    if (focused != null && focused !== dlg.window?.decorView) {
                        focused.performClick(); true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        GamepadDialog.barButton(this, "跳到该位置") {
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
                setBackgroundColor(getColor(R.color.bg_panel))
                addView(jumpBtn)
                addView(GamepadDialog.barButton(this@ReaderActivity, "关闭") { dlg.dismiss() })
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(R.color.bg_panel))
            }
            val scroller = ScrollView(this).apply {
                isFillViewport = true
                // 系统默认滚动条是一条亮色细条，和深色面板差得太远，滚动位置靠内容本身感知
                isVerticalScrollBarEnabled = false
            }
            scroller.addView(root, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            container.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            container.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * d).toInt()))
            dlg.setContentView(container)
            dlg.window?.setLayout((dm.widthPixels * 0.86f).toInt(), (dm.heightPixels * 0.92f).toInt())
            dlg.setOnShowListener { firstFocusable?.requestFocus() }
            dlg.window?.setBackgroundDrawable(GamepadDialog.windowBackground(this))
            dlg.show()
        }
    }

    private fun applySettings(s: ReaderSettings) {
        settings = s
        settingsStore.save(s)
        btnTheme.text = s.theme.label
        pageView.configure(s)
        updateHeader()
    }

    // ---------------- 通用列表弹窗 ----------------

    private fun showListDialog(
        title: String,
        items: Array<String>,
        neutralText: String? = null,
        onNeutral: ((Dialog) -> Unit)? = null,
        initialSelection: Int = -1,
        onPick: (Int) -> Unit
    ) {
        val dm = resources.displayMetrics
        val pad = (16 * dm.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_panel))
            setPadding(pad, pad, pad, 0)
        }
        root.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
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
                tv.setTextColor(getColor(R.color.text_secondary))
                return tv
            }
        }
        val list = ListView(this).apply {
            adapter = listAdapter
            divider = ColorDrawable(getColor(R.color.line))
            dividerHeight = 1
            isFocusable = true
            // 手柄选中高亮与行控件焦点同档：25% 金底
            selector = ColorDrawable(getColor(R.color.accent_focus_bg))
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val dlg = Dialog(this)
        if (neutralText != null) {
            GamepadDialog.barButton(this, neutralText) { onNeutral?.invoke(dlg) }
        }
        bar.addView(GamepadDialog.barButton(this, "关闭") { dlg.dismiss() })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dm.density).toInt()))

        dlg.setContentView(root)
        dlg.window?.setLayout((dm.widthPixels * 0.66f).toInt(), (dm.heightPixels * 0.92f).toInt())
        dlg.window?.setBackgroundDrawable(GamepadDialog.windowBackground(this))
        // ListView 自己接 A：焦点在列表上时，Dialog 那层的 OnKeyListener 收不到（被 View 树先处理）
        // 首屏 selection 未必就绪，退回首可见项，A 键不至于完全无响应
        fun ListView.pickedPosition(): Int {
            val p = selectedItemPosition
            if (p in items.indices) return p
            return firstVisiblePosition.takeIf { it in items.indices } ?: -1
        }
        list.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
            ) {
                val pos = list.pickedPosition()
                if (pos >= 0) {
                    onPick(pos)
                    dlg.dismiss()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        // 手柄 A 不是 ENTER，ListView 不认：焦点在列表上就当成"选中当前项"，
        // 焦点在按钮上就点击按钮。目录、书签两个弹窗都走这里。
        // B 关闭 —— 书库弹窗、菜单层、设置面板都是 B = 关闭，这里不能例外
        dlg.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B -> { dlg.dismiss(); true }
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    val focused = dlg.window?.decorView?.findFocus()
                    when {
                        // hasFocus() 比 findFocus() 实例比对可靠：焦点可能落在列表的子树里
                        list.hasFocus() -> {
                            val pos = list.pickedPosition()
                            if (pos >= 0) {
                                onPick(pos)
                                dlg.dismiss()
                                true
                            } else {
                                false
                            }
                        }
                        focused != null && focused !== dlg.window?.decorView -> {
                            focused.performClick(); true
                        }
                        else -> false
                    }
                }
                else -> false
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            onPick(pos)
            dlg.dismiss()
        }
        dlg.show()
        // 焦点给列表：手柄一进来就是上下选，不用先摸方向键探路
        list.requestFocus()
        if (initialSelection >= 0) {
            // 目录打开时直接定位到当前章，几百章的书不用手动翻
            list.setSelection((initialSelection - 2).coerceAtLeast(0))
        }
    }

    // ---------------- 按键 ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 首次提示浮层显示时，任意按下键让它消失，但继续正常处理该键
        if (::tvFirstHint.isInitialized && tvFirstHint.visibility == View.VISIBLE &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            dismissFirstHint()
        }

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

        if (menuOpen) {
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

        // 音量键翻页（设置里可开）：音量+ 上一页、音量- 下一页，与多数阅读器一致；
        // 连按不连发（音量键长按没有连翻需求），repeat 也吞掉避免中途响音量
        if (settings.volumeKeyTurn &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val a = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    Gamepad.Action.PREV_PAGE
                } else {
                    Gamepad.Action.NEXT_PAGE
                }
                handleAction(a)
            }
            return true
        }

        val action = Gamepad.actionOf(event.keyCode) ?: return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            if (event.repeatCount > 0) {
                if (!Gamepad.isRepeatable(action)) return true
                if (now - lastKeyAt < Gamepad.repeatIntervalMs(action)) return true
            } else if (!Gamepad.isRepeatable(action)) {
                if (now - (lastActionAt[action] ?: 0L) < Gamepad.debounceMs(action)) return true
                lastActionAt[action] = now
            }
            lastKeyAt = now
            if (handleAction(action, event.repeatCount > 0)) return true
        }
        if (event.action == KeyEvent.ACTION_UP) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleAction(a: Gamepad.Action, fromRepeat: Boolean = false): Boolean {
        when (a) {
            Gamepad.Action.PREV_PAGE -> pageView.prevPage(animated = !fromRepeat)
            Gamepad.Action.NEXT_PAGE -> pageView.nextPage(animated = !fromRepeat)
            Gamepad.Action.PREV_CHAPTER -> {
                if (chapters.isEmpty()) return true
                // 边界要给反馈：翻页路径越界有 toast，这里静默重开当前章会闪一下还没解释
                if (chapterIndex > 0) openChapter(chapterIndex - 1, chapters[chapterIndex - 1].start)
                else toast("已经是第一章了")
            }
            Gamepad.Action.NEXT_CHAPTER -> {
                if (chapters.isEmpty()) return true
                if (chapterIndex + 1 < chapters.size) openChapter(chapterIndex + 1, chapters[chapterIndex + 1].start)
                else toast("已经是最后一章了")
            }
            Gamepad.Action.TOGGLE_TOC -> showToc()
            Gamepad.Action.FIND -> showSearch()
            Gamepad.Action.ADD_BOOKMARK -> addBookmark()
            Gamepad.Action.SHOW_BOOKMARKS -> showBookmarks()
            Gamepad.Action.TOGGLE_MENU -> toggleMenu()
            Gamepad.Action.TOGGLE_NIGHT -> toggleNight()
            Gamepad.Action.FONT_DOWN -> changeFont(-1f, fromRepeat)
            Gamepad.Action.FONT_UP -> changeFont(1f, fromRepeat)
            Gamepad.Action.BACK -> {
                if (menuOpen) setMenuVisible(false) else requestExit()
            }
        }
        return true
    }

    /**
     * 后退的双击验证：第一下只提示，[EXIT_CONFIRM_MS] 内再按一次才退回书架。
     * 单手阅读时 B 很容易误触，直接退出会丢掉当前阅读节奏。
     */
    private fun requestExit(hint: String = "再按一次「B」返回书架") {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackPressAt <= EXIT_CONFIRM_MS) {
            lastBackPressAt = 0L
            finish()
            return
        }
        lastBackPressAt = now
        toast(hint)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
