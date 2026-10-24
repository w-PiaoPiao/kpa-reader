package com.kpa.reader.data

import android.content.Context

/**
 * 阅读主题。掌机屏幕是 3.5 寸 960x640，像素密度高，
 * 深色底 + 中等亮度文字在暗环境下最省眼；纸白用于白天。
 */
enum class ReaderTheme(
    val key: String,
    val label: String,
    val bg: Int,
    val fg: Int,
    val sub: Int,
    val accent: Int
) {
    PAPER("paper", "纸白", 0xFFF7F1E0.toInt(), 0xFF2C2A25.toInt(), 0xFF8A8474.toInt(), 0xFFB4763A.toInt()),
    EYE("eye", "护眼", 0xFFCCE8CF.toInt(), 0xFF22331F.toInt(), 0xFF5C7A5A.toInt(), 0xFF2F7A46.toInt()),
    NIGHT("night", "夜间", 0xFF11141A.toInt(), 0xFF9FA9B6.toInt(), 0xFF4C5867.toInt(), 0xFF6FA8DC.toInt()),
    BLACK("black", "纯黑", 0xFF000000.toInt(), 0xFF8C8C8C.toInt(), 0xFF3A3A3A.toInt(), 0xFF5A8FC4.toInt());

    companion object {
        fun of(key: String?): ReaderTheme = entries.firstOrNull { it.key == key } ?: PAPER
    }
}

data class ReaderSettings(
    val fontSizeSp: Float = 16f,
    // 注意：StaticLayout.setLineSpacing 的 multiplier 乘的是字体 metrics 高度
    // （中文衬线字体约 1.37em），所以 1.15 对应视觉上的 1.57 倍行距，正是中文正文的常规值。
    val lineSpacingMult: Float = 1.15f,
    val paragraphSpacingEm: Float = 0.3f,
    val padHdp: Int = 20,
    val padVdp: Int = 10,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val serif: Boolean = true,
    val showStatusBar: Boolean = true,
    /** 按句分段：长自然段拆成一句一段显示，只影响排版，不改字符偏移 */
    val sentenceSegments: Boolean = false,
    /** 音量键翻页：默认关（避免误触调不成音量）；开了之后音量+ 上一页、音量- 下一页 */
    val volumeKeyTurn: Boolean = false
)

class SettingsStore(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    fun load(): ReaderSettings = ReaderSettings(
        fontSizeSp = sp.getFloat("font_size", 16f).coerceIn(12f, 40f),
        lineSpacingMult = sp.getFloat("line_mult", 1.15f).coerceIn(0.85f, 2.2f),
        paragraphSpacingEm = sp.getFloat("para_sp", 0.3f).coerceIn(0f, 1.5f),
        padHdp = sp.getInt("pad_h", 20).coerceIn(4, 80),
        padVdp = sp.getInt("pad_v", 10).coerceIn(4, 80),
        theme = ReaderTheme.of(sp.getString("theme", "paper")),
        serif = sp.getBoolean("serif", true),
        showStatusBar = sp.getBoolean("status_bar", true),
        sentenceSegments = sp.getBoolean("sentence_segments", false),
        volumeKeyTurn = sp.getBoolean("volume_key_turn", false)
    )

    fun save(s: ReaderSettings) {
        sp.edit()
            .putFloat("font_size", s.fontSizeSp)
            .putFloat("line_mult", s.lineSpacingMult)
            .putFloat("para_sp", s.paragraphSpacingEm)
            .putInt("pad_h", s.padHdp)
            .putInt("pad_v", s.padVdp)
            .putString("theme", s.theme.key)
            .putBoolean("serif", s.serif)
            .putBoolean("status_bar", s.showStatusBar)
            .putBoolean("sentence_segments", s.sentenceSegments)
            .putBoolean("volume_key_turn", s.volumeKeyTurn)
            .apply()
    }

    /** 夜间/护眼一键切换：记住上一次的浅色主题 */
    fun quickToggleNight(current: ReaderSettings): ReaderSettings {
        return if (current.theme == ReaderTheme.NIGHT || current.theme == ReaderTheme.BLACK) {
            val back = ReaderTheme.of(sp.getString("theme_day", "paper"))
            current.copy(theme = back)
        } else {
            sp.edit().putString("theme_day", current.theme.key).apply()
            current.copy(theme = ReaderTheme.NIGHT)
        }
    }
}
