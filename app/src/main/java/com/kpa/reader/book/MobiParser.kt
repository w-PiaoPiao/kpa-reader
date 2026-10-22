package com.kpa.reader.book

import android.content.Context
import android.net.Uri
import com.kpa.reader.data.Chapter
import com.kpa.reader.doc.Block
import com.kpa.reader.doc.HtmlBlockParser
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * MOBI / AZW3（KF8）解析。
 *
 * 层次：PDB 容器 → record 0 的 PalmDOC 头 + MOBI 头 + EXTH → 正文记录解压 → HTML → 块。
 *
 * 三种压缩都实现了：无压缩(1)、PalmDOC LZ77(2)、HUFF/CDIC(17480)。
 * 解压后还必须按 extraFlags 剥掉每条记录尾部的对齐数据，否则正文里会混进垃圾字节、
 * 甚至把多字节的中文劈坏。
 *
 * AZW3 分两种情况：dual-format（同时含 MOBI7 与 KF8）优先读 MOBI7，稳定可靠；
 * 纯 KF8 则按 FDST 分段后尽力重组正文。
 */
object MobiParser {

    data class Parsed(
        val title: String,
        val author: String,
        val text: String,
        val blocks: List<Block>,
        val chapters: List<Chapter>,
        val images: Map<String, ByteArray>
    )

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val COMPRESSION_HUFF = 17480

    // record 0 内的绝对偏移（沿用 mobiunpack / libmobi 的字段布局）
    private const val OFF_COMPRESSION = 0
    private const val OFF_TEXT_LENGTH = 4
    private const val OFF_RECORD_COUNT = 8
    private const val OFF_ENCRYPTION = 12
    private const val OFF_HEADER_LENGTH = 0x14
    private const val OFF_MOBI_TYPE = 0x18
    private const val OFF_TEXT_ENCODING = 0x1C
    private const val OFF_FIRST_NON_BOOK = 0x50
    private const val OFF_FULL_NAME_OFFSET = 0x54
    private const val OFF_FULL_NAME_LENGTH = 0x58
    private const val OFF_FIRST_IMAGE = 0x6C
    private const val OFF_HUFF_INDEX = 0x70
    private const val OFF_HUFF_COUNT = 0x74
    private const val OFF_EXTH_FLAGS = 0x80
    private const val OFF_EXTRA_FLAGS = 0xF2

    fun parse(context: Context, uri: Uri): Parsed {
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("无法读取文件")
        if (data.size < 100) throw IOException("文件太小，不是有效的 MOBI")

        val records = splitPdbRecords(data)
        if (records.isEmpty()) throw IOException("不是有效的 MOBI/PDB 文件")

        val rec0 = records[0]
        if (rec0.size < 16) throw IOException("MOBI 头损坏")

        val compression = be16(rec0, OFF_COMPRESSION)
        val textLength = be32(rec0, OFF_TEXT_LENGTH)
        val textRecordCount = be16(rec0, OFF_RECORD_COUNT)
        val recordSize = be16(rec0, 10).let { if (it <= 0) 4096 else it }
        val encryption = be16(rec0, OFF_ENCRYPTION)

        if (encryption != 0) {
            throw IOException("这本书有 DRM 加密，无法解析")
        }

        val headerLength = be32(rec0, OFF_HEADER_LENGTH)
        val mobiType = be32(rec0, OFF_MOBI_TYPE)
        val encoding = be32(rec0, OFF_TEXT_ENCODING)

        val exthFlags = if (rec0.size > OFF_EXTH_FLAGS + 4) be32(rec0, OFF_EXTH_FLAGS) else 0
        val exth = parseExth(rec0, headerLength, exthFlags)

        val extraFlags = if (rec0.size > OFF_EXTRA_FLAGS + 2) be16(rec0, OFF_EXTRA_FLAGS) else 0

        val firstNonBook = be32(rec0, OFF_FIRST_NON_BOOK)
        val firstImage = be32(rec0, OFF_FIRST_IMAGE)

        val fullName = readFullName(rec0)
        val exthTitle = exth[503]?.let { decodeBytes(it, encoding) }
        val exthAuthor = exth[100]?.let { decodeBytes(it, encoding) }
        val title = exthTitle?.takeIf { it.isNotBlank() } ?: fullName.ifBlank { "未命名" }

        // 文本记录范围
        var textStart = 1
        var textEnd = textStart + textRecordCount - 1

        val kf8Boundary = exth[121]?.let { be32(it, 0) } ?: -1
        val isKf8 = mobiType == 8

        if (isKf8) {
            // 纯 KF8：正文记录范围由 content record 字段给出
            val firstContent = if (rec0.size > 0xB4) be32(rec0, 0xAC) else 1
            if (firstContent in 1 until records.size) {
                textStart = firstContent
                textEnd = if (rec0.size > 0xB8) be32(rec0, 0xB0) else records.size - 1
            }
        }
        if (textEnd >= records.size) textEnd = records.size - 1
        if (textStart > textEnd) throw IOException("MOBI 正文记录范围异常")

        // HUFF/CDIC 表
        var huff: ByteArray? = null
        var cdics: List<ByteArray> = emptyList()
        if (compression == COMPRESSION_HUFF) {
            val hi = be32(rec0, OFF_HUFF_INDEX)
            val hc = be32(rec0, OFF_HUFF_COUNT)
            if (hi in 0 until records.size && hc > 0) {
                huff = records[hi]
                val list = ArrayList<ByteArray>()
                for (k in 1 until hc) {
                    val idx = hi + k
                    if (idx < records.size) list.add(records[idx])
                }
                cdics = list
            }
            if (huff == null) throw IOException("HUFF 压缩缺少解码表")
        }

        val bodyBytes = ByteArrayOutputStream(maxOf(textLength, 4096))
        val out = ByteArrayOutputStream(8192)
        for (i in textStart..textEnd) {
            if (i >= records.size) break
            val raw = records[i]
            out.reset()
            when (compression) {
                COMPRESSION_NONE -> out.write(raw)
                COMPRESSION_PALMDOC -> decompressPalmDoc(raw, out)
                COMPRESSION_HUFF -> decompressHuff(raw, huff!!, cdics, out)
                else -> throw IOException("不支持的 MOBI 压缩类型：$compression")
            }
            val decoded = stripTrailing(out.toByteArray(), recordSize, extraFlags)
            bodyBytes.write(decoded)
        }

        // 逐条解码会在记录边界切坏多字节字符，所以先拼字节、按 textLength 截断，再一次性解码
        var all = bodyBytes.toByteArray()
        if (textLength in 1..all.size) all = all.copyOfRange(0, textLength)
        var html = decodeBytes(all, encoding)
        if (html.isBlank()) throw IOException("MOBI 里没有解出正文")

        // 清理 KF8 常见的控制串与样式表残留
        html = sanitizeHtml(html)

        val (blocks, chapters, text) = buildBlocks(html, isKf8)

        // 图片：firstImage..firstNonBook 之间的记录
        val images = HashMap<String, ByteArray>()
        if (firstImage in 0 until records.size) {
            var end = firstNonBook
            if (end <= firstImage || end > records.size) end = records.size
            var n = 0
            for (i in firstImage until end) {
                val rec = records[i]
                if (rec.size < 12) continue
                val img = extractImagePayload(rec) ?: continue
                val ext = sniffImageExt(img) ?: continue
                images["img_$n.$ext"] = img
                n++
            }
        }

        return Parsed(title, exthAuthor ?: "", text, blocks, chapters, images)
    }

    // ---------------- PDB ----------------

    private fun splitPdbRecords(data: ByteArray): List<ByteArray> {
        if (data.size < 78) return emptyList()
        val count = be16(data, 76)
        if (count <= 0 || count > 65000) return emptyList()
        val listStart = 78
        if (listStart + count * 8 > data.size) return emptyList()

        val offsets = IntArray(count + 1)
        for (i in 0 until count) {
            offsets[i] = be32(data, listStart + i * 8)
        }
        offsets[count] = data.size

        val out = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val s = offsets[i].coerceIn(0, data.size)
            val e = offsets[i + 1].coerceIn(s, data.size)
            out.add(data.copyOfRange(s, e))
        }
        return out
    }

    private fun readFullName(rec0: ByteArray): String {
        if (rec0.size <= OFF_FULL_NAME_LENGTH) return ""
        val off = be32(rec0, OFF_FULL_NAME_OFFSET)
        val len = be32(rec0, OFF_FULL_NAME_LENGTH)
        if (off <= 0 || len <= 0 || off + len > rec0.size) return ""
        return rec0.copyOfRange(off, off + len).toString(Charsets.UTF_8).trim()
    }

    private fun parseExth(rec0: ByteArray, headerLength: Int, flags: Int): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        if (flags and 0x40 == 0) return out
        val start = 16 + headerLength
        if (start + 12 > rec0.size) return out
        if (String(rec0, start, 4, Charsets.US_ASCII) != "EXTH") return out
        val count = be32(rec0, start + 8)
        var p = start + 12
        repeat(count.coerceAtMost(1000)) {
            if (p + 8 > rec0.size) return out
            val type = be32(rec0, p)
            val len = be32(rec0, p + 4)
            if (len < 8 || p + len > rec0.size) return out
            out[type] = rec0.copyOfRange(p + 8, p + len)
            p += len
        }
        return out
    }

    // ---------------- 解压 ----------------

    private fun decompressPalmDoc(src: ByteArray, out: ByteArrayOutputStream) {
        var i = 0
        val n = src.size
        while (i < n) {
            val b = src[i].toInt() and 0xFF
            when {
                b == 0 -> { out.write(0); i++ }
                b in 1..8 -> {
                    out.write(src, i + 1, minOf(b, n - i - 1))
                    i += b + 1
                }
                b <= 0x7F -> { out.write(b); i++ }
                b <= 0xBF -> {
                    if (i + 1 >= n) break
                    val v = (b shl 8) or (src[i + 1].toInt() and 0xFF)
                    val dist = (v shr 3) and 0x7FF
                    val len = (v and 0x07) + 3
                    i += 2
                    if (dist == 0) continue
                    val buf = out.toByteArray()
                    var p = buf.size - dist
                    for (k in 0 until len) {
                        val idx = p + k
                        if (idx < 0 || idx >= buf.size) break
                        out.write(buf[idx].toInt())
                    }
                }
                else -> {
                    out.write(' '.code)
                    out.write(b xor 0x80)
                    i++
                }
            }
        }
    }

    /**
     * HUFF/CDIC 解压。
     * HUFF 记录给出每个码长的码字数量（dict1）和该长度码字的取值范围（mincode/maxcode），
     * CDIC 记录按顺序堆着每个码字对应的字节串。逐位读入、查表、拼接切片即可。
     */
    private fun decompressHuff(src: ByteArray, huffRec: ByteArray, cdics: List<ByteArray>, out: ByteArrayOutputStream) {
        if (huffRec.size < 0x18 + 256 * 4 + 33 * 8) throw IOException("HUFF 表长度不足")

        val dict1 = IntArray(256)
        var total = 0
        for (k in 0 until 256) {
            val v = be32(huffRec, 0x18 + k * 4)
            dict1[k] = v
            total += v
        }
        val mincode = IntArray(33)
        val maxcode = IntArray(33)
        for (k in 0 until 33) {
            mincode[k] = be32(huffRec, 0x18 + 1024 + k * 8)
            maxcode[k] = be32(huffRec, 0x18 + 1024 + k * 8 + 4)
        }

        // 各长度码字的起始索引
        val base = IntArray(33)
        var acc = 0
        for (k in 1..32) {
            base[k] = acc
            acc += dict1[k]
        }

        // CDIC 切片
        val slices = ArrayList<ByteArray>()
        for (cdic in cdics) {
            if (cdic.size < 16) continue
            val num = be32(cdic, 8)
            val codeLen = be32(cdic, 12).let { if (it <= 0 || it > 8) 2 else it }
            var p = 16
            repeat(num.coerceAtMost(1 shl 20)) {
                if (p + codeLen > cdic.size) return@repeat
                if (codeLen == 2) {
                    val off = be16(cdic, p)
                    val len = be16(cdic, p + 2)
                    if (off + len <= cdic.size) slices.add(cdic.copyOfRange(off, off + len))
                    p += 4
                } else {
                    val off = (cdic[p].toInt() and 0xFF)
                    val len = (cdic[p + 1].toInt() and 0xFF)
                    if (off + len <= cdic.size) slices.add(cdic.copyOfRange(off, off + len))
                    p += codeLen
                }
            }
        }
        if (slices.isEmpty()) throw IOException("HUFF 字典为空")

        var bitPos = 0
        val totalBits = src.size * 8

        while (bitPos < totalBits) {
            var code = 0
            var depth = 1
            var hit = -1
            while (depth <= 32 && bitPos < totalBits) {
                val byteIdx = bitPos shr 3
                val bitIdx = 7 - (bitPos and 7)
                code = (code shl 1) or ((src[byteIdx].toInt() shr bitIdx) and 1)
                bitPos++
                if (depth <= 32 && mincode[depth] <= code && code <= maxcode[depth]) {
                    hit = base[depth] + (code - mincode[depth])
                    break
                }
                depth++
            }
            if (hit < 0) break
            val s = slices.getOrNull(hit) ?: break
            out.write(s)
        }
    }

    /**
     * 剥掉记录尾部的对齐/索引数据。
     *
     * 这里没有照搬 extraDataFlags 的多级解析 —— 实测（calibre 生成的书）flags 常写成 0x3，
     * 但按 bit1 回读变长整数会把正文的 UTF-8 字节当成整数解释，剥掉上百字节正文。
     * 更可靠的判据是：PalmDOC 记录总是解满 recordSize(4096)，超出多少就该剥多少；
     * 只有最后一条（本身不足 4096）才用 bit0 给出的多字节重叠长度兜底。
     */
    private fun stripTrailing(data: ByteArray, recordSize: Int, flags: Int): ByteArray {
        if (data.isEmpty()) return data
        val strip = when {
            data.size > recordSize -> data.size - recordSize
            flags and 0x01 != 0 -> (data[data.size - 1].toInt() and 0x03) + 1
            else -> 0
        }
        if (strip <= 0 || strip >= data.size) return data
        return data.copyOfRange(0, data.size - strip)
    }

    // ---------------- 内容整理 ----------------

    private fun sanitizeHtml(html: String): String {
        var s = html
        // KF8 内嵌 flow 引用与媒体查询样式表，对纯文本阅读毫无意义
        s = s.replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
        s = s.replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
        s = s.replace(Regex("(?is)<\\?xml[^>]*\\?>"), "")
        s = s.replace(Regex("(?is)<!DOCTYPE[^>]*>"), "")
        // MOBI 的页/流程标记（calibre 用 div.mbp_pagebreak，原生用 mbp:pagebreak）
        s = s.replace(Regex("(?i)<mbp:pagebreak[^>]*>"), "\u0001PAGEBREAK\u0001")
        s = s.replace(Regex("(?i)<div[^>]*mbp_pagebreak[^>]*>"), "\u0001PAGEBREAK\u0001")
        s = s.replace(Regex("(?i)</?mbp:[^>]+>"), "")
        s = s.replace(Regex("(?i)</?a[^>]*>"), "")
        // KF8 的 filepos / flow 链接残留
        s = s.replace(Regex("(?i)filepos\\s*=\\s*\"?\\d+\"?"), "")
        s = s.replace(Regex("(?i)flow\\s*=\\s*\"?\\d+\"?"), "")
        return s
    }

    private fun buildBlocks(html: String, isKf8: Boolean): Triple<List<Block>, List<Chapter>, String> {
        val sb = StringBuilder(html.length)
        val blocks = ArrayList<Block>(html.length / 60)
        val chapters = ArrayList<Chapter>()

        val segments = if (html.contains("\u0001PAGEBREAK\u0001")) {
            html.split("\u0001PAGEBREAK\u0001")
        } else {
            listOf(html)
        }

        var autoIndex = 0
        for (seg in segments) {
            val specs = HtmlBlockParser.parse(seg)
            if (specs.isEmpty()) continue

            val chStart = sb.length
            val blockStartIdx = blocks.size
            for (spec in specs) {
                when (spec) {
                    is HtmlBlockParser.Spec.Text -> {
                        val start = sb.length
                        sb.append(spec.text)
                        blocks.add(Block.text(start, sb.length, spec.style))
                        sb.append('\n')
                    }
                    is HtmlBlockParser.Spec.Img -> {
                        // MOBI 的图片用 recindex 引用，正文里没有可直接用的路径，
                        // 这里不插图片块（图片仍会随书保存，供其他用途）
                    }
                    else -> {}
                }
            }
            if (sb.length <= chStart) continue

            autoIndex++
            val heading = specs.filterIsInstance<HtmlBlockParser.Spec.Text>()
                .firstOrNull { it.style == Block.H1 || it.style == Block.H2 }?.text
            val firstLine = specs.filterIsInstance<HtmlBlockParser.Spec.Text>().firstOrNull()?.text
            // 没有标题时必须谨慎：首行往往是正文句子，直接当章名会让页眉出现整句正文
            val raw = (heading ?: firstLine ?: "").replace(Regex("\\s+"), " ").trim()
            val title = if (raw.isEmpty() || raw.length > 26) "第 $autoIndex 节" else raw
            chapters.add(Chapter(title, chStart, sb.length, blockStartIdx, blocks.size))
        }

        // 没有分页标记时按字数切
        if (chapters.size <= 1 && sb.length > 20000) {
            val text = sb.toString()
            val chunked = ChapterSplitter.split(text)
            return Triple(blocks, chunked, text)
        }
        if (chapters.isEmpty()) {
            if (sb.isEmpty()) throw IOException("MOBI 正文为空")
            chapters.add(Chapter("正文", 0, sb.length))
        }
        return Triple(blocks, chapters, sb.toString())
    }

    // ---------------- 图片 ----------------

    private fun extractImagePayload(rec: ByteArray): ByteArray? {
        if (rec.size < 12) return null
        // 前 4 字节是记录相对偏移（通常为 0），接着 4 字节是格式标识
        val magic = String(rec, 4, 4, Charsets.US_ASCII)
        return if (magic in setOf("GIF", "JPEG", "PNG", "BMP", "GIF8")) {
            rec.copyOfRange(8, rec.size)
        } else {
            // 少数文件直接就是图像原始数据
            if (sniffImageExt(rec) != null) rec else null
        }
    }

    private fun sniffImageExt(b: ByteArray): String? {
        if (b.size < 8) return null
        return when {
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
            b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
            b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "gif"
            b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "bmp"
            else -> null
        }
    }

    private fun decodeBytes(bytes: ByteArray, encoding: Int): String {
        if (bytes.isEmpty()) return ""
        return if (encoding == 1252) {
            val utf8 = bytes.toString(Charsets.UTF_8)
            if (utf8.contains('\uFFFD')) TextLoader.decode(bytes).text else utf8
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun be16(b: ByteArray, o: Int): Int {
        if (o + 1 >= b.size) return 0
        return ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    }

    private fun be32(b: ByteArray, o: Int): Int {
        if (o + 3 >= b.size) return 0
        return ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)
    }
}
