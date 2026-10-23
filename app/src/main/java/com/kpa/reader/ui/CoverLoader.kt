package com.kpa.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.kpa.reader.book.BookImporter
import com.kpa.reader.data.Book
import java.io.File
import java.util.concurrent.Executors

/**
 * 书架封面加载：后台解码 + 内存缓存。
 *
 * 书架是网格滚动，直接在 onBind 里 decodeFile 会掉帧；
 * 缓存按「书 id + 封面文件名」做键，同一本书重复绑定不再解码。
 */
object CoverLoader {

    /** 解到这个尺寸就够显示（卡片封面区约 225×177px），再大纯属浪费内存 */
    private const val TARGET_W = 420
    private const val TARGET_H = 340

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /**
     * [onReady] 一定在主线程回调；没有封面（TXT、书里没图）时回 null，
     * 由调用方显示占位。
     */
    fun load(context: Context, book: Book, onReady: (Bitmap?) -> Unit) {
        if (book.cover.isBlank()) {
            onReady(null)
            return
        }
        val key = "${book.id}@${book.cover}"
        cache.get(key)?.let {
            onReady(it)
            return
        }
        val file = BookImporter.imageFile(context.applicationContext, book.id, book.cover)
        if (!file.exists()) {
            onReady(null)
            return
        }
        pool.execute {
            val bmp = decode(file)
            if (bmp != null) cache.put(key, bmp)
            main.post { onReady(bmp) }
        }
    }

    /** 回收某本书的缓存（删除书时调用） */
    fun evict(book: Book) = cache.remove("${book.id}@${book.cover}")

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_W &&
            bounds.outHeight / (sample * 2) >= TARGET_H
        ) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }.getOrNull()
}
