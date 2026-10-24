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
        SHOW_BOOKMARKS,
        TOGGLE_MENU,
        TOGGLE_NIGHT,
        FONT_DOWN,
        FONT_UP,
        BACK
    }

    /**
     * 键码 → 动作。
     *
     * 先查用户自定义映射（[Keymap]），再落到固定键：十字键翻页、BACK 后退、
     * 以及外接键盘的辅助键。固定键不可自定义 —— 那是导航基础，
     * 改坏了连设置页都退不出去。
     */
    fun actionOf(keyCode: Int): Action? {
        Keymap.actionFor(keyCode)?.let { return it }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP -> Action.PREV_PAGE

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> Action.NEXT_PAGE

            // 外接键盘辅助键，不影响手柄
            KeyEvent.KEYCODE_F -> Action.FIND
            KeyEvent.KEYCODE_MENU -> Action.TOGGLE_MENU
            KeyEvent.KEYCODE_N -> Action.TOGGLE_NIGHT
            KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_LEFT_BRACKET -> Action.FONT_DOWN
            KeyEvent.KEYCODE_PLUS,
            KeyEvent.KEYCODE_RIGHT_BRACKET,
            KeyEvent.KEYCODE_EQUALS -> Action.FONT_UP

            // B 默认是后退；若用户把它绑给了别的动作，上面查表已经命中，
            // 这里就轮不到 —— 那种情况后退还有系统返回键
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> Action.BACK

            else -> null
        }
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

    /**
     * 按键提示文案。按当前映射动态生成，用户在设置里改过键之后帮助也对得上。
     */
    fun helpLines(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(14)
        out.add("十字键" to "上下左右翻页")
        for (a in Keymap.BINDABLE) {
            val code = Keymap.codeOf(a)
            if (code != Keymap.UNBOUND) out.add(Keymap.name(code) to Keymap.label(a))
        }
        out.add("B" to "后退（连按两次返回书架）")
        out.add("触摸" to "左右点击翻页，中间呼出菜单")
        return out
    }
}
