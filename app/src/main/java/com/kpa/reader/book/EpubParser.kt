package com.kpa.reader.book

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kpa.reader.data.Chapter
import com.kpa.reader.doc.Block
import com.kpa.reader.doc.HtmlBlockParser
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * EPUB 解析。
 *
 * 三点与「只提纯文字」的旧版本不同：
 * 1. 图片会被解出来供阅读器渲染（含封面、插图）；
 * 2. 目录优先取 EPUB3 的 nav.xhtml，其次 EPUB2 的 toc.ncx，最后退化成 h1/文件名；
 * 3. 输出块序列而不是一个字符串，标题层级会保留下来。
 */
object EpubParser {

    data class Parsed(
        val title: String,
        val author: String,
        val text: String,
        val blocks: List<Block>,
        val chapters: List<Chapter>,
        val images: Map<String, ByteArray>,
        /** OPF 里声明的封面图（zip 路径）；空表示这本书没声明封面 */
        val cover: String = ""
    )

    private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
    private const val TAG = "EpubParser"

    private val OPF_ITEM = Regex("<item\\s+[^>]*>", RegexOption.IGNORE_CASE)
    private val SPINE_REF = Regex("<itemref\\s+[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z:_-]+)\\s*=\\s*\"([^\"]*)\"")

    private val KEEP_EXT = listOf(".xhtml", ".html", ".htm", ".opf", ".ncx", ".xml")
    private val IMG_EXT = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")

    /** 收集 zip 内的文本/图片 entry。先流式读（快），读不完整再走 ZipFile 兜底 */
    private fun readEntries(context: Context, uri: Uri): HashMap<String, ByteArray> {
        val streamed = readViaStream(context, uri)
        if (streamed != null && hasOpf(streamed)) return streamed
        Log.w(TAG, "流式读取不可用（异常或残缺，entries=${streamed?.size}），走 ZipFile 兜底")
        return readViaZipFile(context, uri)
    }

    /** 后续解析全靠 OPF：流式结果里没有它就当读取失败 */
    private fun hasOpf(entries: Map<String, ByteArray>): Boolean =
        entries.keys.any { it.endsWith(".opf", true) } ||
            entries.containsKey("META-INF/container.xml")

    /**
     * 流式读取。不少网文工具生成的 EPUB 会给 STORE 条目也标 data descriptor ——
     * 桌面 JVM 会抛 ZipException，但 Android 的实现不抛，而是静默读出残缺数据。
     * 所以这里「不抛异常 ≠ 成功」，调用方必须用 [hasOpf] 校验结果。
     */
    private fun readViaStream(context: Context, uri: Uri): HashMap<String, ByteArray>? {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开 EPUB")
        try {
            val entries = HashMap<String, ByteArray>()
            var imageBytes = 0L
            ZipInputStream(BufferedInputStream(input)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val lower = name.lowercase()
                    // 每条数据都必须消费掉（哪怕丢弃），否则 data descriptor 错位、后面全崩
                    val bytes = zis.readBytes()
                    when {
                        KEEP_EXT.any { lower.endsWith(it) } -> entries[name] = bytes
                        IMG_EXT.any { lower.endsWith(it) } ->
                            if (imageBytes < MAX_IMAGE_BYTES) {
                                entries[name] = bytes
                                imageBytes += bytes.size
                            }
                    }
                }
            }
            return entries
        } catch (e: ZipException) {
            Log.w(TAG, "流式读取抛异常: $e")
            return null
        } finally {
            runCatching { input.close() }
        }
    }

    /** 兜底：拷到临时文件用 ZipFile 按中央目录读，对非标准 zip 宽容得多 */
    private fun readViaZipFile(context: Context, uri: Uri): HashMap<String, ByteArray> {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开 EPUB")
        val tmp = File.createTempFile("epub_", ".zip", context.cacheDir)
        try {
            tmp.outputStream().use { input.copyTo(it) }
            val entries = HashMap<String, ByteArray>()
            var imageBytes = 0L
            ZipFile(tmp).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val entry = en.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val lower = name.lowercase()
                    val bytes = zf.getInputStream(entry).readBytes()
                    when {
                        KEEP_EXT.any { lower.endsWith(it) } -> entries[name] = bytes
                        IMG_EXT.any { lower.endsWith(it) } ->
                            if (imageBytes < MAX_IMAGE_BYTES) {
                                entries[name] = bytes
                                imageBytes += bytes.size
                            }
                    }
                }
            }
            Log.w(TAG, "ZipFile 兜底: entries=${entries.size} container=${entries.containsKey("META-INF/container.xml")} " +
                "opfKeys=${entries.keys.filter { it.endsWith(".opf", true) }} " +
                "firstKeys=${entries.keys.take(5)}")
            return entries
        } catch (e: ZipException) {
            throw IOException("这个 EPUB 的 zip 数据损坏，换个来源重新下载试试")
        } finally {
            runCatching { input.close() }
            tmp.delete()
        }
    }

    fun parse(context: Context, uri: Uri): Parsed {
        val entries = readEntries(context, uri)

        val containerXml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
        val opfPath = containerXml
            ?.let { Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            ?: entries.keys.firstOrNull { it.endsWith(".opf", true) }
            ?: throw IOException("这个 EPUB 里找不到章节清单（OPF），文件可能不完整，换个来源重新下载试试")

        val opf = entries[opfPath]?.toString(Charsets.UTF_8) ?: throw IOException("这个 EPUB 里找不到章节清单（OPF），文件可能不完整，换个来源重新下载试试")
        val baseDir = opfPath.substringBeforeLast('/', "")

        val manifest = HashMap<String, String>()
        OPF_ITEM.findAll(opf).forEach { m ->
            val attrs = parseAttrs(m.value)
            val id = attrs["id"] ?: return@forEach
            val href = attrs["href"] ?: return@forEach
            val type = attrs["media-type"] ?: ""
            if (type.contains("html") || href.endsWith(".xhtml", true) || href.endsWith(".html", true)) {
                manifest[id] = href
            }
        }

        val order = ArrayList<String>()
        SPINE_REF.findAll(opf).forEach { m ->
            val idref = parseAttrs(m.value)["idref"] ?: return@forEach
            manifest[idref]?.let { if (!order.contains(it)) order.add(it) }
        }
        if (order.isEmpty()) manifest.values.sorted().forEach { order.add(it) }

        val bookTitle = Regex("<dc:title[^>]*>(.*?)</dc:title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(opf)?.groupValues?.get(1)?.let { unescape(it) } ?: "未命名"

        val author = Regex("<dc:creator[^>]*>(.*?)</dc:creator>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(opf)?.groupValues?.get(1)?.let { unescape(it) } ?: ""

        val toc = readToc(entries, opfPath, baseDir)

        // 封面按 OPF 声明取，不能拿「正文第一张图」凑数 ——
        // 很多书第一张图是扉页、印章或出版社 logo（《剑来》就是这样取错的）
        val coverPath = readCoverHref(opf, baseDir)

        val sb = StringBuilder()
        val blocks = ArrayList<Block>()
        val chapters = ArrayList<Chapter>()
        val usedImages = LinkedHashSet<String>()

        for (href in order) {
            val path = resolvePath(baseDir, href)
            // 处理完一章就从内存里扔掉一章：几千章的书全量驻留会把掌机堆打爆
            val bytes = entries.remove(path) ?: entries.remove(href) ?: continue
            val html = decodeHtml(bytes)
            val specs = HtmlBlockParser.parse(html)
            if (specs.isEmpty()) continue

            val chStart = sb.length
            val blockStartIdx = blocks.size
            val pageDir = path.substringBeforeLast('/', "")
            var addedImage = false

            for (spec in specs) {
                when (spec) {
                    is HtmlBlockParser.Spec.Text -> {
                        val start = sb.length
                        sb.append(spec.text)
                        blocks.add(Block.text(start, sb.length, spec.style))
                        sb.append('\n')
                    }
                    is HtmlBlockParser.Spec.Img -> {
                        val imgPath = resolvePath(pageDir, spec.src)
                        val real = entries.keys.firstOrNull { it == imgPath }
                            ?: entries.keys.firstOrNull { it.equals(imgPath, ignoreCase = true) }
                        if (real != null) {
                            usedImages.add(real)
                            blocks.add(Block.image(real, sb.length))
                            addedImage = true
                        }
                    }
                    else -> {}
                }
            }

            // 封面页可能只有一张图、没有任何文字，也要单独成章，否则图会跑到下一章
            if (sb.length <= chStart && !addedImage) continue
            val title = toc[href] ?: toc[path]
                ?: specs.filterIsInstance<HtmlBlockParser.Spec.Text>()
                    .firstOrNull { it.style == Block.H1 || it.style == Block.H2 }?.text
                ?: if (addedImage) "插图" else "第 ${chapters.size + 1} 节"
            chapters.add(Chapter(cleanTitle(title), chStart, sb.length, blockStartIdx, blocks.size))
        }

        if (sb.isEmpty()) throw IOException("EPUB 中没有可读文本")

        val images = HashMap<String, ByteArray>()
        usedImages.forEach { k -> entries[k]?.let { images[k] = it } }

        // 封面即使正文没引用（没有封面页的 EPUB 很常见）也要存进去，否则书架没图可用
        var realCover = ""
        if (coverPath.isNotBlank()) {
            val real = entries.keys.firstOrNull { it == coverPath }
                ?: entries.keys.firstOrNull { it.equals(coverPath, ignoreCase = true) }
            if (real != null) {
                images[real] = entries.getValue(real)
                realCover = real
            }
        }

        return Parsed(bookTitle, author, sb.toString(), blocks, chapters, images, realCover)
    }

    /**
     * 找 OPF 声明的封面图：
     * 1. EPUB3 `<item properties="cover-image">`
     * 2. EPUB2 `<meta name="cover" content="item-id">`
     * 3. 兜底：id 或文件名里带 cover 的图片
     */
    private fun readCoverHref(opf: String, baseDir: String): String {
        val items = OPF_ITEM.findAll(opf).map { parseAttrs(it.value) }.toList()

        val epub3 = items.firstOrNull { (it["properties"] ?: "").contains("cover-image", true) }
            ?.get("href")

        val metaId = Regex("<meta\\s+[^>]*name\\s*=\\s*\"cover\"[^>]*>", RegexOption.IGNORE_CASE)
            .find(opf)?.let { parseAttrs(it.value)["content"] }
        val epub2 = metaId?.let { id -> items.firstOrNull { it["id"] == id }?.get("href") }

        val byName = items.firstOrNull { a ->
            val href = a["href"] ?: ""
            IMG_EXT.any { href.endsWith(it, true) } &&
                ((a["id"] ?: "").contains("cover", true) || href.contains("cover", true))
        }?.get("href")

        val href = epub3 ?: epub2 ?: byName ?: return ""
        return resolvePath(baseDir, href)
    }

    private fun readToc(entries: MutableMap<String, ByteArray>, opfPath: String, baseDir: String): Map<String, String> {
        val out = HashMap<String, String>()
        val opf = entries[opfPath]?.toString(Charsets.UTF_8) ?: return out

        // EPUB3: <item properties="nav" href="nav.xhtml">
        val navHref = OPF_ITEM.findAll(opf).map { parseAttrs(it.value) }
            .firstOrNull { (it["properties"] ?: "").contains("nav") }?.get("href")

        if (navHref != null) {
            val p = resolvePath(baseDir, navHref)
            val nav = entries[p]?.toString(Charsets.UTF_8)
            entries.remove(p)
            if (nav != null) {
                // 目录基准路径必须从「路径 p」算 —— nav 变量是整个文件内容，
                // 拿它当 baseDir 会把 235KB 的 HTML 拼进每个目录项路径，大书直接 OOM
                val dir = if (p.contains('/')) p.substringBeforeLast('/') else ""
                Regex("<a\\s[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .findAll(nav).forEach { m ->
                        val href = m.groupValues[1].substringBefore('#')
                        val text = HtmlBlockParser.decodeEntities(m.groupValues[2].replace(Regex("<[^>]+>"), "")).trim()
                        if (href.isNotEmpty() && text.isNotEmpty()) {
                            val resolved = resolvePath(dir, href)
                            out.putIfAbsent(resolved, text)
                            out.putIfAbsent(href, text)
                        }
                    }
                if (out.isNotEmpty()) return out
            }
        }

        // EPUB2: toc.ncx
        val ncxPath = OPF_ITEM.findAll(opf).map { parseAttrs(it.value) }
            .firstOrNull { (it["media-type"] ?: "").contains("ncx") }?.get("href")
        if (ncxPath != null) {
            val p = resolvePath(baseDir, ncxPath)
            val ncx = entries[p]?.toString(Charsets.UTF_8)
            entries.remove(p)
            if (ncx != null) {
                // 注意：ncx 就在根目录时 substringBeforeLast('/') 会返回整个文件名，
                // 那样拼出来的 href 全是错的，必须显式判空
                val dir = if (p.contains('/')) p.substringBeforeLast('/') else ""
                Regex("<navPoint[^>]*>(.*?)</navPoint>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .findAll(ncx).forEach { m ->
                        val body = m.groupValues[1]
                        val src = Regex("<content[^>]*src\\s*=\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                        val label = Regex("<text[^>]*>(.*?)</text>",
                            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                            .find(body)?.groupValues?.get(1)
                        if (src != null && label != null) {
                            val clean = HtmlBlockParser.decodeEntities(label.replace(Regex("<[^>]+>"), "")).trim()
                            val href = src.substringBefore('#')
                            out.putIfAbsent(resolvePath(dir, href), clean)
                            out.putIfAbsent(href, clean)
                        }
                    }
            }
        }
        return out
    }

    private fun decodeHtml(bytes: ByteArray): String {
        // xhtml 规范要求 UTF-8；个别老文件是 GBK，靠宽松解码兜底
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) TextLoader.decode(bytes).text else utf8
    }

    private fun cleanTitle(t: String): String =
        t.replace(Regex("[\\s\\u00A0]+"), " ").trim().take(60).ifEmpty { "正文" }

    private fun parseAttrs(tag: String): Map<String, String> {
        val map = HashMap<String, String>()
        ATTR.findAll(tag).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }
        return map
    }

    private fun resolvePath(baseDir: String, href: String): String {
        val clean = href.substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val combined = if (decoded.startsWith("/")) {
            decoded.trimStart('/')
        } else if (baseDir.isEmpty()) {
            decoded
        } else {
            "$baseDir/$decoded"
        }
        // 归一化 ./ 和 ../
        val parts = ArrayList<String>()
        for (seg in combined.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun unescape(s: String): String =
        HtmlBlockParser.decodeEntities(s.replace(Regex("<[^>]+>"), "")).trim()
}
