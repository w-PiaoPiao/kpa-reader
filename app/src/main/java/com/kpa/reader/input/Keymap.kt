package com.kpa.reader.input

import android.content.Context
import android.view.KeyEvent

/**
 * 可自定义的按键映射。
 *
 * 只让「功能键」可改（A/B/X/Y/L1/R1/L2/R2/START/SELECT）：
 * 十字键固定用来翻页，BACK 固定后退 —— 这两个是导航基础，改坏了连设置页都出不去。
 *
 * 一个动作只占一个可自定义键；十字键、PAGE_UP/DOWN、ENTER 这类仍是固定备用，
 * 由 [Gamepad.actionOf] 兜底，所以改映射不会让某个功能彻底失联。
 */
object Keymap {

    private const val SP_NAME = "reader_keys"

    /** 设置页里的展示顺序也按这个来 */
    val BINDABLE: List<Gamepad.Action> = listOf(
        Gamepad.Action.NEXT_PAGE,
        Gamepad.Action.PREV_PAGE,
        Gamepad.Action.NEXT_CHAPTER,
        Gamepad.Action.PREV_CHAPTER,
        Gamepad.Action.ADD_BOOKMARK,
        Gamepad.Action.SHOW_BOOKMARKS,
        Gamepad.Action.TOGGLE_TOC,
        Gamepad.Action.FIND,
        Gamepad.Action.TOGGLE_MENU,
        Gamepad.Action.TOGGLE_NIGHT
    )

    /**
     * 允许被绑定的物理键。
     * 不含十字键（翻页导航的基础）、BACK/B（捕获时 B 用于取消，且 B 是后退键）。
     */
    private val ALLOWED = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT
    )

    private fun defaults(): Map<Gamepad.Action, Int> = mapOf(
        Gamepad.Action.NEXT_PAGE to KeyEvent.KEYCODE_BUTTON_A,
        // 上一页默认不占功能键：十字键 ← ↑ 够用，L1 让给加书签
        Gamepad.Action.PREV_PAGE to UNBOUND,
        Gamepad.Action.NEXT_CHAPTER to KeyEvent.KEYCODE_BUTTON_R1,
        Gamepad.Action.PREV_CHAPTER to KeyEvent.KEYCODE_BUTTON_R2,
        Gamepad.Action.ADD_BOOKMARK to KeyEvent.KEYCODE_BUTTON_L1,
        Gamepad.Action.SHOW_BOOKMARKS to KeyEvent.KEYCODE_BUTTON_L2,
        Gamepad.Action.TOGGLE_TOC to KeyEvent.KEYCODE_BUTTON_X,
        Gamepad.Action.FIND to KeyEvent.KEYCODE_BUTTON_Y,
        Gamepad.Action.TOGGLE_MENU to KeyEvent.KEYCODE_BUTTON_START,
        Gamepad.Action.TOGGLE_NIGHT to KeyEvent.KEYCODE_BUTTON_SELECT
    )

    const val UNBOUND = -1

    @Volatile
    private var bindings: Map<Gamepad.Action, Int> = defaults()

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    /** 幂等，Activity 入口各调一次即可 */
    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val map = defaults().toMutableMap()
        for (action in BINDABLE) {
            if (sp.contains(action.name)) map[action] = sp.getInt(action.name, UNBOUND)
        }
        bindings = map
        prefs = sp
    }

    fun codeOf(action: Gamepad.Action): Int = bindings[action] ?: UNBOUND

    /** 反向查：这个物理键当前绑给了哪个动作 */
    fun actionFor(keyCode: Int): Gamepad.Action? =
        BINDABLE.firstOrNull { bindings[it] == keyCode && keyCode != UNBOUND }

    fun canBind(keyCode: Int): Boolean = keyCode in ALLOWED

    /**
     * 绑定。[keyCode] 已被别的动作占用时两者**交换**，
     * 而不是把对方置空 —— 否则用户改一次映射就会莫名其妙丢一个功能。
     */
    fun bind(action: Gamepad.Action, keyCode: Int) {
        val old = codeOf(action)
        val holder = actionFor(keyCode)
        val map = bindings.toMutableMap()
        map[action] = keyCode
        if (holder != null && holder != action) map[holder] = old
        bindings = map
        prefs?.edit()?.apply {
            map.forEach { (a, code) -> putInt(a.name, code) }
        }?.apply()
    }

    fun resetAll() {
        bindings = defaults()
        prefs?.edit()?.apply {
            bindings.forEach { (a, code) -> putInt(a.name, code) }
        }?.apply()
    }

    fun label(action: Gamepad.Action): String = when (action) {
        Gamepad.Action.NEXT_PAGE -> "下一页"
        Gamepad.Action.PREV_PAGE -> "上一页"
        Gamepad.Action.NEXT_CHAPTER -> "下一章"
        Gamepad.Action.PREV_CHAPTER -> "上一章"
        Gamepad.Action.ADD_BOOKMARK -> "加书签"
        Gamepad.Action.SHOW_BOOKMARKS -> "书签列表"
        Gamepad.Action.TOGGLE_TOC -> "目录"
        Gamepad.Action.FIND -> "全文搜索"
        Gamepad.Action.TOGGLE_MENU -> "菜单"
        Gamepad.Action.TOGGLE_NIGHT -> "夜间模式"
        else -> action.name
    }

    fun name(keyCode: Int): String = when (keyCode) {
        UNBOUND -> "未设置"
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_START -> "START"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "SELECT"
        KeyEvent.KEYCODE_DPAD_UP -> "十字键上"
        KeyEvent.KEYCODE_DPAD_DOWN -> "十字键下"
        KeyEvent.KEYCODE_DPAD_LEFT -> "十字键左"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "十字键右"
        else -> "键码 $keyCode"
    }
}
