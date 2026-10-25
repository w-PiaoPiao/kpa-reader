package com.kpa.reader.book

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本加载与编码探测。
 *
 * 中文 TXT 的编码五花八门：老书 GBK/GB18030，新书 UTF-8，偶尔 UTF-16。
 * 策略：BOM 优先 → UTF-8 严格解码试一次（失败必抛异常）→ GB18030 兜底。
 * GB18030 是 GBK 的超集，老书基本都能正确解出来。
 */
object TextLoader {

    /** 单本上限，超过就拒绝，避免 OOM（掌机只有 3~4GB，且要留给系统） */
    const val MAX_BYTES = 24 * 1024 * 1024

    data class Loaded(val text: String, val encoding: String)

    private val CLEANUP = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u200B-\\u200F\\uFEFF]")
    private val TRAILING_SPACES = Regex("[ \\t\\u3000]+\\n")
    private val MANY_BLANK_LINES = Regex("\\n{3,}")

    /**
     * 行首空白直接清掉。中文 TXT 里很多书自带两个全角空格的段首缩进，
     * 而阅读器排版时会用 LeadingMarginSpan 再加一次缩进，不清就会变成四字缩进。
     */
    private val LEADING_SPACES = Regex("(?m)^[ \\t\\u3000]{1,12}(?=\\S)")

    fun load(context: Context, uri: Uri): Loaded {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream(1 shl 16)
            val buf = ByteArray(1 shl 16)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IOException("文件超过 ${MAX_BYTES / 1024 / 1024}MB，暂不支持")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("无法读取文件")

        return decode(bytes)
    }

    fun decode(bytes: ByteArray): Loaded {
        // 1. BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Loaded(normalize(String(bytes, 3, bytes.size - 3, Charsets.UTF_8)), "UTF-8")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Loaded(normalize(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)), "UTF-16LE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Loaded(normalize(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)), "UTF-16BE")
        }

        // 2. 严格 UTF-8
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val cs = decoder.decode(ByteBuffer.wrap(bytes))
            return Loaded(normalize(cs.toString()), "UTF-8")
        } catch (_: CharacterCodingException) {
            // 不是合法 UTF-8，往下走
        }

        // 3. 无 BOM 的 UTF-16：含足够 ASCII 时半个字节是 0x00，统计奇偶偏移的
        //    0x00 占比即可判别方向。GBK 合法流几乎不含 0x00 字节，不会误触；
        //    纯 CJK 的无 BOM UTF-16 没有这个特征，只能漏判（无解，罕见）。
        if (bytes.size >= 64) {
            val pairs = bytes.size / 2
            var evenZero = 0
            var oddZero = 0
            for (k in 0 until pairs) {
                if (bytes[k * 2] == 0.toByte()) evenZero++
                if (bytes[k * 2 + 1] == 0.toByte()) oddZero++
            }
            val cs = when {
                oddZero * 10 > pairs * 3 -> Charsets.UTF_16LE
                evenZero * 10 > pairs * 3 -> Charsets.UTF_16BE
                else -> null
            }
            if (cs != null) return Loaded(normalize(String(bytes, cs)), cs.name())
        }

        // 4. GB18030 / GBK：合法中文文件不会解出替换符；解出一堆 U+FFFD
        //    说明字节根本不是中文编码（典型：西文老编码），别硬吃
        val gb = runCatching { Charset.forName("GB18030") }.getOrNull()
        if (gb != null) {
            val s = String(bytes, gb)
            if (badRatio(s) < 0.01f) return Loaded(normalize(s), "GB18030")
        }

        // 5. 西文老编码（windows-1252 一系）。CP1252 全字节空间有映射，几乎不产生
        //    替换符；但 NUL 除外 —— UTF-16 字节流硬解 1252 会大量 NUL，用它在门口拦掉
        runCatching { Charset.forName("windows-1252") }.getOrNull()?.let { cs ->
            val s = String(bytes, cs)
            if (badRatio(s) < 0.01f && s.count { it == '\u0000' } <= s.length / 100) {
                return Loaded(normalize(s), "windows-1252")
            }
        }

        // 6. 实在不行，替换解码
        return Loaded(normalize(String(bytes, Charsets.UTF_8)), "UTF-8(替换)")
    }

    /** 替换符占比：合法文本应接近 0，编错码时会冒出一串 */
    private fun badRatio(s: String): Float {
        if (s.isEmpty()) return 0f
        var bad = 0
        for (c in s) if (c == '\uFFFD') bad++
        return bad.toFloat() / s.length
    }

    /** 统一换行、清掉控制符与零宽字符、压缩多余空行 */
    fun normalize(raw: String): String {
        var s = raw
        if (s.isNotEmpty() && s[0] == '\uFEFF') s = s.substring(1)
        s = s.replace("\r\n", "\n").replace('\r', '\n')
        s = CLEANUP.replace(s, "")
        s = TRAILING_SPACES.replace(s, "\n")
        s = MANY_BLANK_LINES.replace(s, "\n\n")
        s = LEADING_SPACES.replace(s, "")
        return s
    }
}
