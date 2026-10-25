package com.kpa.reader.book

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kpa.reader.data.Book
import com.kpa.reader.data.Chapter
import com.kpa.reader.doc.Block
import com.kpa.reader.doc.Document
import java.io.File
import java.util.UUID

/**
 * 把外部文件变成一条书库记录，并把解析结果落盘：
 *
 *   books/<id>.txt      全书纯文本（搜索与进度的基准）
 *   books/<id>.blocks   块结构（段落/标题/图片，渲染用）
 *   books/<id>.img/     图片文件
 *
 * 先落盘再打开的好处：EPUB 只解一次 zip、MOBI 只解一次压；原文件被删也不影响阅读。
 */
object BookImporter {

    data class Content(
        val title: String,
        val author: String,
        val text: String,
        val blocks: List<Block>,
        val chapters: List<Chapter>,
        val images: Map<String, ByteArray>,
        val format: String,
        val encoding: String,
        /** 解析器声明的封面（images 里的原始 key）；空表示没声明 */
        val cover: String = ""
    )

    suspend fun import(context: Context, uri: Uri): Book {
        val name = queryDisplayName(context, uri) ?: "未命名"
        val mime = context.contentResolver.getType(uri) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()

        val title = name.substringBeforeLast('.').ifBlank { "未命名" }

        val content: Content = when {
            ext == "epub" || mime == "application/epub+zip" -> {
                val p = EpubParser.parse(context, uri)
                Content(
                    title = p.title.ifBlank { title },
                    author = p.author,
                    text = p.text,
                    blocks = p.blocks,
                    chapters = p.chapters,
                    images = p.images,
                    format = "epub",
                    encoding = "UTF-8",
                    cover = p.cover
                )
            }
            ext in setOf("mobi", "azw", "azw3", "prc", "azw4", "pdb") ||
                mime.contains("mobipocket") || mime.contains("x-mobipocket") -> {
                val p = MobiParser.parse(context, uri)
                Content(
                    title = p.title.ifBlank { title },
                    author = p.author,
                    text = p.text,
                    blocks = p.blocks,
                    chapters = p.chapters,
                    images = p.images,
                    format = if (ext == "azw3") "azw3" else "mobi",
                    encoding = "UTF-8"
                )
            }
            else -> {
                val l = TextLoader.load(context, uri)
                val tBlocks = Document.blocksFromPlainText(l.text)
                // 纯文本也要把章节映射到块区间，保持和 EPUB/MOBI 一致的分页路径
                val tChapters = ChapterSplitter.split(l.text).map { ch ->
                    val bs = tBlocks.indexOfLast { it.start <= ch.start }.coerceAtLeast(0)
                    var be = tBlocks.indexOfLast { it.start < ch.end } + 1
                    if (be <= bs) be = (bs + 1).coerceAtMost(tBlocks.size)
                    ch.copy(blockStart = bs, blockEnd = be)
                }
                Content(
                    title = title,
                    author = "",
                    text = l.text,
                    blocks = tBlocks,
                    chapters = tChapters,
                    images = emptyMap(),
                    format = "txt",
                    encoding = l.encoding
                )
            }
        }

        if (content.text.isBlank()) throw IllegalStateException("没有读到任何文本")

        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "books").apply { mkdirs() }

        val (blocks, images, nameMap) = remapImages(content.blocks, content.images)

        File(dir, "$id.txt").writeText(content.text, Charsets.UTF_8)
        File(dir, "$id.blocks").writeText(Document(content.text, blocks).serialize(), Charsets.UTF_8)

        if (images.isNotEmpty()) {
            val imgDir = File(dir, "$id.img").apply { mkdirs() }
            images.forEach { (n, data) -> runCatching { File(imgDir, n).writeBytes(data) } }
        }

        // 封面优先级：解析器声明的（EPUB 的 OPF cover）> 正文第一张图 > 提取出的第一张
        // MOBI/AZW3 的图片按 recindex 引用、不进 blocks，所以最后一步是给它们兜底的。
        // 按 img_N 的 N 数值排：字典序会把 img_10 排到 img_2 前面。
        val coverName = nameMap[content.cover]
            ?: blocks.firstOrNull { it.isImage }?.image
            ?: images.keys.sortedBy { it.imageOrdinal() }.firstOrNull()
            ?: ""

        val now = System.currentTimeMillis()
        return Book(
            id = id,
            title = content.title.ifBlank { title },
            uri = uri.toString(),
            format = content.format,
            encoding = content.encoding,
            charCount = content.text.length,
            chapters = content.chapters,
            progressOffset = 0,
            progressPercent = 0f,
            addedAt = now,
            lastReadAt = now,
            author = content.author,
            hasImages = images.isNotEmpty(),
            cover = coverName
        )
    }

    /** 把解析器给的图片 key（zip 路径/序号）统一改成本地文件名，并重写块引用 */
    private fun remapImages(
        blocks: List<Block>,
        raw: Map<String, ByteArray>
    ): Triple<List<Block>, Map<String, ByteArray>, Map<String, String>> {
        if (raw.isEmpty()) {
            return Triple(blocks.filter { !it.isImage }, emptyMap(), emptyMap())
        }
        val nameMap = HashMap<String, String>(raw.size)
        val out = LinkedHashMap<String, ByteArray>(raw.size)
        var n = 0
        for ((key, data) in raw) {
            val e = key.substringAfterLast('.', "jpg").lowercase()
            val ext = if (e in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) e else "jpg"
            val local = "img_$n.$ext"
            nameMap[key] = local
            out[local] = data
            n++
        }
        val nb = blocks.mapNotNull { b ->
            if (!b.isImage) b else nameMap[b.image]?.let { b.copy(image = it) }
        }
        return Triple(nb, out, nameMap)
    }

    fun imageDir(context: Context, bookId: String): File =
        File(File(context.filesDir, "books"), "$bookId.img")

    fun imageFile(context: Context, bookId: String, name: String): File =
        File(imageDir(context, bookId), name)

    fun removeCache(context: Context, bookId: String) {
        val dir = File(context.filesDir, "books")
        runCatching { File(dir, "$bookId.txt").delete() }
        runCatching { File(dir, "$bookId.blocks").delete() }
        runCatching { File(dir, "$bookId.img").deleteRecursively() }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}

/** "img_12.jpg" → 12；不匹配生成式的名字排最后（保持稳定序） */
private fun String.imageOrdinal(): Int =
    substringAfter("img_", missingDelimiterValue = "")
        .takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
