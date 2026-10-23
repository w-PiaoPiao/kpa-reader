package com.kpa.reader.data

import android.content.Context
import com.kpa.reader.doc.Document
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 书库持久化。
 *
 * 写盘走单线程后台队列：翻页时每 0.7 秒会更新一次进度，如果同步写整个 JSON，
 * 主线程会被磁盘 IO 卡住（书多的时候尤其明显）。这里只更新内存，
 * 序列化和写文件丢给 IO 线程，并把短时间内的多次请求合并成一次。
 *
 * 退出前调用 [flush] 确保落盘。
 */
class BookStore private constructor(private val appContext: Context) {

    private val file = File(appContext.filesDir, "library.json")
    private val lock = Any()
    private val books = ArrayList<Book>()

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "book-store-io").apply { isDaemon = true }
    }
    private val pending = AtomicBoolean(false)

    init {
        load()
        migrateCovers()
    }

    private fun load() {
        synchronized(lock) {
            books.clear()
            if (!file.exists()) return
            runCatching {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val b = Book.fromJson(o)
                    if (b.id.isNotEmpty()) books.add(b)
                }
            }
        }
    }

    /**
     * 老数据没有 cover 字段（书架改版前导入的书）：从 blocks 里补第一张图当封面。
     * 只处理「有图但没记封面」的书，无图的 TXT 直接跳过，所以开销很小。
     */
    private fun migrateCovers() {
        val dir = File(appContext.filesDir, "books")
        var changed = false
        synchronized(lock) {
            for (i in books.indices) {
                val b = books[i]
                if (b.cover.isNotBlank() || !b.hasImages) continue
                val blockFile = File(dir, "${b.id}.blocks")
                if (!blockFile.exists()) continue
                val cover = runCatching {
                    Document.parseBlocks(blockFile.readText(Charsets.UTF_8))
                        .firstOrNull { it.isImage }?.image
                }.getOrNull() ?: runCatching {
                    // MOBI/AZW3 的图片不进 blocks，退回去看 img 目录里编号最小的那张
                    File(dir, "${b.id}.img").listFiles()
                        ?.sortedBy { it.name }?.firstOrNull()?.name
                }.getOrNull()
                if (!cover.isNullOrBlank()) {
                    books[i] = b.copy(cover = cover)
                    changed = true
                }
            }
        }
        if (changed) persist()
    }

    fun all(): List<Book> = synchronized(lock) { books.sortedByDescending { it.lastReadAt } }

    fun find(id: String): Book? = synchronized(lock) { books.firstOrNull { it.id == id } }

    fun findByUri(uri: String): Book? = synchronized(lock) { books.firstOrNull { it.uri == uri } }

    fun upsert(book: Book) {
        synchronized(lock) {
            val idx = books.indexOfFirst { it.id == book.id }
            if (idx >= 0) books[idx] = book else books.add(book)
        }
        persist()
    }

    fun remove(id: String) {
        synchronized(lock) { books.removeAll { it.id == id } }
        persist()
    }

    fun updateProgress(id: String, offset: Int, percent: Float) {
        synchronized(lock) {
            val idx = books.indexOfFirst { it.id == id }
            if (idx < 0) return
            val b = books[idx]
            books[idx] = b.copy(
                progressOffset = offset,
                progressPercent = percent.coerceIn(0f, 1f),
                lastReadAt = System.currentTimeMillis(),
                finished = percent >= 0.995f
            )
        }
        persist()
    }

    private fun persist() {
        pending.set(true)
        runCatching {
            io.execute {
                // 合并：队列里排着多个请求时，只有第一个真正执行
                if (!pending.compareAndSet(true, false)) return@execute
                val snapshot = synchronized(lock) { ArrayList(books) }
                runCatching {
                    val arr = JSONArray()
                    snapshot.forEach { arr.put(it.toJson()) }
                    val tmp = File(file.parentFile, "library.json.tmp")
                    tmp.writeText(arr.toString(), Charsets.UTF_8)
                    if (file.exists()) file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    /** 等待挂起的写入完成（onPause / 进程可能被杀前调用） */
    fun flush(timeoutMs: Long = 400) {
        runCatching { io.submit { }.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    companion object {
        @Volatile
        private var instance: BookStore? = null

        fun get(context: Context): BookStore {
            return instance ?: synchronized(this) {
                instance ?: BookStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
