package com.kpa.reader

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kpa.reader.book.BookImporter
import com.kpa.reader.data.Book
import com.kpa.reader.data.BookStore
import com.kpa.reader.input.Gamepad
import com.kpa.reader.input.Keymap
import com.kpa.reader.ui.ReaderActivity
import com.kpa.reader.ui.ShelfAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架。
 *
 * 掌机上没有鼠标，整个书架必须能纯用十字键 + A 键操作：
 * item 设为可聚焦，焦点态有金色描边，A 键映射为 performClick。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: BookStore
    private lateinit var adapter: ShelfAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View

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
        recycler = findViewById(R.id.recycler)
        emptyView = findViewById(R.id.emptyView)

        val span = (resources.configuration.screenWidthDp / 150).coerceIn(2, 5)
        recycler.layoutManager = GridLayoutManager(this, span)
        adapter = ShelfAdapter(
            onClick = { open(it) },
            onLongClick = { confirmDelete(it) }
        )
        recycler.adapter = adapter

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
    }

    override fun onPause() {
        store.flush()
        super.onPause()
    }

    private fun refresh() {
        val list = store.all()
        adapter.submit(list)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isNotEmpty() && recycler.childCount > 0) {
            recycler.post { recycler.getChildAt(0)?.requestFocus() }
        }
    }

    private fun open(book: Book) {
        startActivity(ReaderActivity.intentOf(this, book.id))
    }

    private fun startImport(uri: android.net.Uri) {
        val existing = store.findByUri(uri.toString())
        if (existing != null) {
            toast("《${existing.title}》已经在书架里了")
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setImporting(true, "正在解析…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BookImporter.import(this@MainActivity, uri) }
            }
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
        val msg = when {
            e is OutOfMemoryError -> "文件太大，内存不够。可以先压缩图片或换个小一点的版本。"
            e is SecurityException -> "没有读取这个文件的权限，试试把它复制到「下载」目录再导入。"
            e is java.io.IOException -> e.message?.takeIf { it.isNotBlank() }
                ?: "读取文件失败，可能文件不完整或已损坏。"
            e is IllegalStateException -> e.message ?: "这个文件里没有可读的文字。"
            else -> e.message?.takeIf { it.isNotBlank() }
                ?: "解析失败（${e.javaClass.simpleName}）"
        }
        AlertDialog.Builder(this)
            .setTitle("导入失败")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun confirmDelete(book: Book) {
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("从书架移除《${book.title}》？\n（只删本地缓存，原文件不受影响）")
            .setPositiveButton("删除") { _, _ ->
                store.remove(book.id)
                BookImporter.removeCache(this, book.id)
                toast("已移除")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHelp() {
        val text = buildString {
            appendLine("【书架】")
            appendLine("· 十字键移动焦点，A 键打开")
            appendLine("· 长按菜单键（或触摸长按卡片）删除")
            appendLine()
            appendLine("【阅读中】")
            Gamepad.helpLines().forEach { (k, v) -> appendLine("· $k　→　$v") }
        }
        AlertDialog.Builder(this)
            .setTitle("操作说明")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 手柄 A 键在系统里不是 ENTER，需要手动转成点击
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_START
            ) {
                val focused = currentFocus
                if (focused != null && event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                    focused.performClick()
                    return true
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
                showHelp(); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
