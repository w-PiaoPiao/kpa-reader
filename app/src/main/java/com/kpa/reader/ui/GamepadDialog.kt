package com.kpa.reader.ui

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kpa.reader.R

/**
 * 手柄弹窗共用件：全 app 弹窗的视觉与按键行为从这里出，别再各写一套或用系统 AlertDialog。
 *
 * 视觉：面板底 + 12dp 圆角 + 48dp 底栏（与各界面顶栏同高）；
 * 按键：A = 点击焦点控件（手柄 A 不是 ENTER，Dialog 独立窗口里没人转）、B = 关闭。
 */
object GamepadDialog {

    /** 弹窗底栏高度（dp）：书库顶栏、阅读菜单栏同高 */
    const val BAR_DP = 48

    fun windowBackground(context: Context): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 12 * context.resources.displayMetrics.density
        setColor(context.getColor(R.color.bg_panel))
    }

    /** 弹窗底栏文字按钮：焦点 = 25% 金底（行控件焦点档） */
    fun barButton(context: Context, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding((18 * context.resources.displayMetrics.density).toInt(), 0,
                (18 * context.resources.displayMetrics.density).toInt(), 0)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(
                    if (hasFocus) context.getColor(R.color.accent_focus_bg) else 0
                )
            }
        }

    /** A/B 键处理。有额外按键逻辑（改键捕获、列表选中）的弹窗自己挂 listener，别走这个 */
    fun attachPadKeys(dlg: Dialog) {
        dlg.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B -> { dlg.dismiss(); true }
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    val focused = dlg.window?.decorView?.findFocus()
                    if (focused != null && focused !== dlg.window?.decorView) {
                        focused.performClick(); true
                    } else false
                }
                else -> false
            }
        }
    }
}

/**
 * 标题 + 正文 + 底栏按钮的确认/提示弹窗（删除确认、导入失败、操作说明这类）。
 * 双按钮时初始焦点给否定侧（手柄误按 A 不至于直接执行破坏性操作），单按钮给唯一按钮。
 */
fun showPadMessageDialog(
    context: Context,
    title: String,
    message: String,
    positiveText: String,
    onPositive: () -> Unit = {},
    negativeText: String? = null,
    onNegative: () -> Unit = {}
) {
    val dm = context.resources.displayMetrics
    val d = dm.density
    val pad = (16 * d).toInt()

    var positiveBtn: TextView? = null
    var negativeBtn: TextView? = null

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(context.getColor(R.color.bg_panel))
        setPadding(pad, pad, pad, 0)
    }
    root.addView(TextView(context).apply {
        text = title
        setTextColor(context.getColor(R.color.text_primary))
        textSize = 15f
        setPadding(0, 0, 0, (10 * d).toInt())
    })
    root.addView(ScrollView(context).apply {
        addView(TextView(context).apply {
            text = message
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 13f
            setLineSpacing(2 * d, 1f)
        })
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    root.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        negativeText?.let { neg ->
            negativeBtn = GamepadDialog.barButton(context, neg) { onNegative() }.also { addView(it) }
        }
        positiveBtn = GamepadDialog.barButton(context, positiveText) { onPositive() }
            .also { addView(it) }
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, (GamepadDialog.BAR_DP * d).toInt()
    ))

    val dlg = Dialog(context)
    dlg.setContentView(root)
    dlg.window?.setLayout((dm.widthPixels * 0.72f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    dlg.window?.setBackgroundDrawable(GamepadDialog.windowBackground(context))
    GamepadDialog.attachPadKeys(dlg)
    dlg.show()
    // 双按钮聚焦安全侧（取消），单按钮聚焦唯一按钮
    (negativeBtn ?: positiveBtn)?.requestFocus()
}
