package com.kpa.reader

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kpa.reader.book.BookImporter
import com.kpa.reader.data.Book
import com.kpa.reader.data.BookStore
import com.kpa.reader.input.Gamepad
import com.kpa.reader.input.Keymap
import com.kpa.reader.ui.ReaderActivity
import com.kpa.reader.ui.ShelfAdapter
import com.kpa.reader.ui.showPadMessageDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 书库首页：Switch 主机式横排封面卡。
 *
 * 掌机上没有鼠标，整个书库必须能纯用十字键 + A 键操作：
 * 卡片可聚焦，焦点即选中（放大 + 白描边），书名/进度显示在底部信息面板。
 * L1/R1 整屏翻动，X 删除，Y 看按键说明，A 键映射为 performClick。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: BookStore
    private lateinit var adapter: ShelfAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var infoPanel: View
    private lateinit var hintBar: View
    private lateinit var tvPanelTitle: TextView
    private lateinit var tvPanelSub: TextView
    private lateinit var tvPanelPct: TextView
    private lateinit var tvPanelLast: TextView
    private lateinit var tvPanelCount: TextView
    private lateinit var pbPanel: ProgressBar

    /** 面板跟随着焦点走；触屏没有焦点概念，点击即打开，面板停在最后一次选中 */
    private var selectedPos = 0
    private val dateFmt = SimpleDateFormat("MM-dd", Locale.getDefault())

    /** 书架排序。切换存 SharedPreferences，跨启动记住 */
    private enum class Sort(val label: String, val shortLabel: String) {
        LAST_READ("最近阅读", "最近"),
        ADDED("加入时间", "加入"),
        TITLE("书名", "书名")
    }
    private var sort = Sort.LAST_READ

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateStatus()
            clockHandler.postDelayed(this, 30_000L)
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == android.app.Activity.RESULT_OK && uri != null) startImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        store = BookStore.get(this)
        Keymap.init(this)
        sort = runCatching { Sort.valueOf(sortPrefs().getString("shelf_sort", null) ?: "") }
            .getOrDefault(Sort.LAST_READ)
        recycler = findViewById(R.id.recycler)
        emptyView = findViewById(R.id.emptyView)
        infoPanel = findViewById(R.id.infoPanel)
        hintBar = findViewById(R.id.hintBar)
        tvPanelTitle = findViewById(R.id.tvPanelTitle)
        tvPanelSub = findViewById(R.id.tvPanelSub)
        tvPanelPct = findViewById(R.id.tvPanelPct)
        tvPanelLast = findViewById(R.id.tvPanelLast)
        tvPanelCount = findViewById(R.id.tvPanelCount)
        pbPanel = findViewById(R.id.pbPanel)

        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        adapter = ShelfAdapter(
            onClick = { open(it) },
            onLongClick = { confirmDelete(it) },
            onFocus = { b, pos ->
                selectedPos = pos
                updatePanel(b, pos, adapter.itemCount)
                smoothCenter(pos)
            }
        )
        recycler.adapter = adapter
        // 选中卡锁死屏幕正中：左右 padding = (屏宽-卡宽)/2（clipToPadding 已关）。
        // 这样 scroll=0 时第一张就正好居中，其余每本都能滚到正中 ——
        // padding 再小，前面几本书就需要负向滚动（不可达），永远差一截。
        recycler.doOnLayout { rv ->
            // 卡 104dp + 左右 margin 5dp = 114dp，与 item_book.xml 对应
            val cardSpan = dp(114)
            val side = ((rv.width - cardSpan) / 2).coerceAtLeast(dp(16))
            rv.setPadding(side, 0, side, 0)
        }

        findViewById<View>(R.id.btnImport).setOnClickListener {
            // 不用 OpenDocument 契约：它一定会带上 EXTRA_MIME_TYPES 白名单，
            // 而 Android 不认识 .mobi / .azw3（系统报成「PRC 文件」和「BIN 文件」），
            // 白名单一过滤它们就不见了。这里只给 type=*/*，让选择器不做 MIME 过滤。
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            runCatching { picker.launch(intent) }
        }
        findViewById<View>(R.id.btnHelp).setOnClickListener { showHelp() }
        updateSortLabel()
        findViewById<View>(R.id.btnSort).setOnClickListener { cycleSort() }

        handleExternalIntent(intent)
    }

    /** 支持从外部（其他 App / adb）指定某本书并直接搜索 */
    private fun handleExternalIntent(intent: Intent?) {
        val q = intent?.getStringExtra(ReaderActivity.EXTRA_SEARCH) ?: return
        val id = intent.getStringExtra(ReaderActivity.EXTRA_ID)
        if (!id.isNullOrBlank()) {
            startActivity(ReaderActivity.intentOf(this, id, q))
            intent.removeExtra(ReaderActivity.EXTRA_SEARCH)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        clockHandler.post(clockTick)
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockTick)
        store.flush()
        super.onPause()
    }

    private fun refresh() {
        val list = sortedBooks()
        adapter.submit(list, continuePosition(list))
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        infoPanel.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        hintBar.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tvLibCount).text = "共 ${list.size} 本"
        if (list.isEmpty()) {
            updatePanel(null, 0, 0)
            return
        }
        selectedPos = selectedPos.coerceIn(0, list.size - 1)
        // submit 之后必须等这一轮 layout 跑完才有 ViewHolder 可聚焦 ——
        // 普通 post 排在 vsync 的 doFrame 之前，会拿到 null，焦点就被顶栏按钮抢走
        recycler.doOnNextLayout { recycler.post { focusSelected() } }
    }

    private fun sortPrefs() =
        getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    private fun sortedBooks(): List<Book> = when (sort) {
        // store.all() 本身就按 lastReadAt 降序
        Sort.LAST_READ -> store.all()
        Sort.ADDED -> store.all().sortedByDescending { it.addedAt }
        Sort.TITLE -> {
            val cmp = java.text.Collator.getInstance(Locale.CHINA)
            store.all().sortedWith { a, b -> cmp.compare(a.title, b.title) }
        }
    }

    /** 「继续」角标挂最近在读且没读完的那本，排序换了也不能挂错卡 */
    private fun continuePosition(list: List<Book>): Int =
        list.indexOfFirst { !it.finished && it.progressPercent < 1f && it.lastReadAt > 0 }

    private fun updateSortLabel() {
        findViewById<TextView>(R.id.btnSort).text = "排序·${sort.shortLabel}"
    }

    private fun cycleSort() {
        sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]
        sortPrefs().edit().putString("shelf_sort", sort.name).apply()
        updateSortLabel()
        selectedPos = 0
        refresh()
        toast("按${sort.label}排序")
    }

    private fun focusSelected() {
        // 焦点要给卡片本体（cardBox），定位容器不可聚焦
        recycler.findViewHolderForAdapterPosition(selectedPos)?.itemView
            ?.findViewById<View>(R.id.cardBox)?.requestFocus()
        // 触摸模式下 requestFocus 会被吞，面板直接刷，不依赖焦点回调
        updatePanel(adapter.itemAt(selectedPos), selectedPos, adapter.itemCount)
    }

    private fun updatePanel(b: Book?, pos: Int, total: Int) {
        if (b == null) {
            tvPanelTitle.text = "—"
            tvPanelSub.text = ""
            tvPanelPct.text = ""
            tvPanelLast.text = ""
            tvPanelCount.text = ""
            pbPanel.progress = 0
            return
        }
        tvPanelTitle.text = "《${b.title}》"
        val author = b.author.ifBlank { "佚名" }
        tvPanelSub.text = "$author · ${b.format.uppercase()} · ${b.chapters.size} 章 · 书签 ${b.bookmarks.size}"
        tvPanelPct.text = "${(b.progressPercent * 100).toInt().coerceIn(0, 100)}%"
        tvPanelLast.text = when {
            b.finished -> "已读完"
            b.lastReadAt > 0 -> "上次 " + dateFmt.format(Date(b.lastReadAt))
            else -> "未读过"
        }
        tvPanelCount.text = "${pos + 1} / $total"
        pbPanel.progress = (b.progressPercent * 1000).toInt().coerceIn(0, 1000)
    }

    /** 顶栏电量 + 时间。掌机沉浸模式没有系统状态栏，书库页自己显示；
     *  合成一条「86% · 20:07」，比两个 TextView 各占一段更紧凑 */
    private fun updateStatus() {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        findViewById<TextView>(R.id.tvClock).text = if (pct >= 0) "$pct% · $time" else time
    }

    private fun focusedBook(): Book? {
        val v = recycler.focusedChild ?: return null
        val pos = recycler.getChildAdapterPosition(v)
        return if (pos == RecyclerView.NO_POSITION) null else adapter.itemAt(pos)
    }

    /**
     * 十字键 / L1R1 全部走这里：把选中挪到 [delta] 方向的卡片，并滚到屏幕正中。
     * 不交给系统焦点导航 —— 它只会贴边滚，做不到「选中即居中」。
     */
    private fun moveSelection(delta: Int) {
        val n = adapter.itemCount
        if (n == 0) return
        val cur = recycler.focusedChild
            ?.let { recycler.getChildAdapterPosition(it) }
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?: selectedPos
        val target = (cur + delta).coerceIn(0, n - 1)
        if (target == cur) return
        selectedPos = target
        val vh = recycler.findViewHolderForAdapterPosition(target)
        if (vh != null) {
            vh.itemView.findViewById<View>(R.id.cardBox).requestFocus()
        } else {
            // 跨页跳转：目标还没布局，先瞬移过去，布局完再补焦点
            (recycler.layoutManager as LinearLayoutManager).scrollToPosition(target)
            recycler.post { focusSelected() }
        }
        // 触摸模式下 requestFocus 会被吞，面板直接刷，不依赖焦点回调
        updatePanel(adapter.itemAt(target), target, n)
    }

    /** 平滑滚动，让 [pos] 那本书停在屏幕正中（焦点回调里调用，目标 view 必定已布局） */
    private fun smoothCenter(pos: Int) {
        val target = (recycler.layoutManager as LinearLayoutManager).findViewByPosition(pos) ?: return
        // 卡中心在屏中心右边 → dx 为正 → 内容左移追上它；反之往回滚
        val dx = (target.left + target.right) / 2 - recycler.width / 2
        if (dx != 0) recycler.smoothScrollBy(dx, 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun open(book: Book) {
        startActivity(ReaderActivity.intentOf(this, book.id))
    }

    private fun startImport(uri: android.net.Uri) {
        // 防重入：查重靠 findByUri，导入完成前记录还没落库，连按导入键会并发跑两份
        if (importing) return
        val existing = store.findByUri(uri.toString())
        if (existing != null) {
            toast("《${existing.title}》已经在书架里了")
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        importing = true
        setImporting(true, "正在解析…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BookImporter.import(this@MainActivity, uri) }
            }
            importing = false
            setImporting(false)
            result.onSuccess { book ->
                store.upsert(book)
                store.flush()
                toast("已导入《${book.title}》· ${book.chapters.size} 章")
                refresh()
            }.onFailure { e ->
                showImportError(e)
            }
        }
    }

    private fun setImporting(on: Boolean, text: String = "正在解析…") {
        findViewById<View>(R.id.importOverlay).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvImportState).text = text
    }

    /** 把异常翻译成人话：用户看到「Not a zip file」只会更困惑 */
    private fun showImportError(e: Throwable) {
        Log.e(TAG_LOG, "导入失败", e)
        val msg = when {
            e is OutOfMemoryError -> "文件太大，内存不够。可以先压缩图片或换个小一点的版本。"
            e is SecurityException -> "没有读取这个文件的权限，试试把它复制到「下载」目录再导入。"
            e is java.io.IOException -> e.message?.takeIf { it.isNotBlank() }
                ?: "读取文件失败，可能文件不完整或已损坏。"
            e is IllegalStateException -> e.message ?: "这个文件里没有可读的文字。"
            else -> e.message?.takeIf { it.isNotBlank() }
                ?: "解析失败（${e.javaClass.simpleName}）"
        }
        showPadMessageDialog(this, "导入失败", msg, "知道了")
    }

    private fun confirmDelete(book: Book) {
        showPadMessageDialog(
            this,
            "删除",
            "从书架移除《${book.title}》？\n（只删本地缓存，原文件不受影响）",
            positiveText = "删除",
            onPositive = {
                store.remove(book.id)
                BookImporter.removeCache(this, book.id)
                toast("已移除")
                refresh()
            },
            negativeText = "取消"
        )
    }

    private fun showHelp() {
        val text = buildString {
            appendLine("【书库】")
            appendLine("· 十字键选书，A 键打开")
            appendLine("· X 键删除（触摸长按卡片也可以）")
            appendLine("· L1/R1 整屏翻动")
            appendLine("· 顶栏「排序」切换排序方式")
            appendLine("· Y 键看本说明")
            appendLine()
            appendLine("【阅读中】")
            Gamepad.helpLines().forEach { (k, v) -> appendLine("· $k　→　$v") }
        }
        showPadMessageDialog(this, "操作说明", text, "知道了")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // 焦点在卡片行里时左右/翻页自己接管（系统只会贴边滚，做不到选中即居中）；
            // 焦点在顶栏按钮上时左右交还系统，在「按键 / 导入」间切换
            val inCardRow = recycler.focusedChild != null
            when (event.keyCode) {
                // 手柄 A 键在系统里不是 ENTER，需要手动转成点击
                KeyEvent.KEYCODE_BUTTON_A -> {
                    currentFocus?.performClick()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_X -> {
                    if (event.repeatCount == 0 && inCardRow) focusedBook()?.let { confirmDelete(it) }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (event.repeatCount == 0) showHelp()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    // B 在书库 = 从顶栏回到当前选中的书（和下键同一条路）；
                    // 卡片行里保持无操作，别让误触把选中书顶乱
                    if (!inCardRow) focusSelected()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (inCardRow) {
                        moveSelection(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                        return true
                    }
                    // 顶栏：交给系统
                }
                // 上键全程交给系统：卡片行 → 跳上方最近的顶栏按钮
                KeyEvent.KEYCODE_DPAD_UP -> return super.dispatchKeyEvent(event)
                // 下键：顶栏回到当前选中的书；卡片行内下移无意义，吞掉防跳去面板
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!inCardRow) focusSelected()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> {
                    if (inCardRow) moveSelection(-PAGE_STEP)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_R1 -> {
                    if (inCardRow) moveSelection(PAGE_STEP)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** L1/R1 一次翻过的卡片数，与一屏可见张数一致 */
        private const val PAGE_STEP = 5
        private const val TAG_LOG = "Import"
    }

    /** 导入进行中（主线程读写，lifecycleScope 默认主线程派发） */
    private var importing = false
}
