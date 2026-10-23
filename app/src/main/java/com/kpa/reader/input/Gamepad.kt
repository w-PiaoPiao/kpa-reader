package com.kpa.reader.input

import android.view.KeyEvent

/**
 * 掌机实体按键映射。
 *
 * KONKR Pocket Advance 的按键由 AYASpace 映射成标准 Android 手柄键值
 * （KEYCODE_BUTTON_A/B/X/Y/L1/R1/L2/R2/START/SELECT）。但不同固件/固件版本
 * 偶尔会把 D-pad 报成 MotionEvent 的 HAT 轴，所以 [ReaderActivity] 对
 * KeyEvent 和 GenericMotionEvent 两条通道都要处理。
 *
 * 映射原则：翻页只占最容易按到的键（左右 + A/B + L1/R1），
 * 低频功能放 X/Y/L2/R2，避免读书时误触。
 */
object Gamepad {

    enum class Action {
        PREV_PAGE,
        NEXT_PAGE,
        PREV_CHAPTER,
        NEXT_CHAPTER,
        TOGGLE_TOC,
        FIND,
        ADD_BOOKMARK,
        TOGGLE_MENU,
        TOGGLE_NIGHT,
        FONT_DOWN,
        FONT_UP,
        BACK
    }

    fun actionOf(keyCode: Int): Action? = when (keyCode) {
        // 翻页：十字键左右 / L1 / R1 / 翻页键
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_PAGE_UP -> Action.PREV_PAGE

        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER -> Action.NEXT_PAGE

        // 章
        KeyEvent.KEYCODE_BUTTON_L2 -> Action.PREV_CHAPTER
        KeyEvent.KEYCODE_BUTTON_R2 -> Action.NEXT_CHAPTER

        // 功能
        KeyEvent.KEYCODE_BUTTON_X -> Action.TOGGLE_TOC
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_F -> Action.FIND
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_MENU -> Action.TOGGLE_MENU

        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_N -> Action.TOGGLE_NIGHT

        KeyEvent.KEYCODE_MINUS,
        KeyEvent.KEYCODE_LEFT_BRACKET -> Action.FONT_DOWN

        KeyEvent.KEYCODE_PLUS,
        KeyEvent.KEYCODE_RIGHT_BRACKET,
        KeyEvent.KEYCODE_EQUALS -> Action.FONT_UP

        // 后退：B 不再翻页，改为返回（需双击确认，见 ReaderActivity）
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE -> Action.BACK

        else -> null
    }

    /** 哪些动作允许长按连发 */
    fun isRepeatable(action: Action): Boolean = when (action) {
        Action.PREV_PAGE, Action.NEXT_PAGE,
        Action.PREV_CHAPTER, Action.NEXT_CHAPTER,
        Action.FONT_DOWN, Action.FONT_UP -> true
        else -> false
    }

    /**
     * 非连发动作的去抖间隔。
     *
     * 大部分动作 260ms —— 有些固件会把一次按下报成两个 ACTION_DOWN，
     * 不去抖的话夜间模式会被瞬间切回去。
     * 但「后退」要支持快速双击确认，阈值必须小于人双击的间隔，否则第二下会被吃掉。
     */
    fun debounceMs(action: Action): Long = when (action) {
        Action.BACK -> 120L
        else -> 260L
    }

    /** 连发间隔（毫秒）——掌机按键回弹快，80ms 左右连翻手感刚好 */
    const val REPEAT_INTERVAL_MS = 80L

    /** 按键提示文案，用于阅读器内的帮助浮层 */
    val HELP_LINES: List<Pair<String, String>> = listOf(
        "十字键 ← → / ↑ ↓" to "上一页 / 下一页",
        "A / R1" to "下一页",
        "L1" to "上一页",
        "L2 / R2" to "上一章 / 下一章",
        "X" to "目录",
        "Y" to "全文搜索",
        "START" to "菜单（书签、跳转、字号、主题）",
        "SELECT" to "夜间模式",
        "B" to "后退（连按两次返回书架）",
        "触摸" to "左/右侧点击翻页，中间呼出菜单"
    )
}
