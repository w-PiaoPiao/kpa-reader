package com.kpa.reader.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.kpa.reader.R
import com.kpa.reader.data.Book

/**
 * 书库横排卡片（Switch 主菜单式）：一排大封面，选中的放大 + 白描边，
 * 书名/作者/进度等信息统一放到底部信息面板，卡片本身不再挤文字。
 *
 * 焦点即选中：十字键走系统焦点导航，缩放动画和面板刷新都挂在焦点变化上。
 * 触屏行为不变：点按打开、长按删除。
 */
class ShelfAdapter(
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit,
    private val onFocus: (Book, Int) -> Unit
) : RecyclerView.Adapter<ShelfAdapter.VH>() {

    private var items: List<Book> = emptyList()

    /** 「继续」角标所在位置；-1 表示没有正在读的书 */
    private var continuePos = -1

    /** 封面占位底色：按书 id 稳定取一个，不同书不会都是同一个灰块 */
    private val placeholderColors = intArrayOf(
        0xFF36485C.toInt(), 0xFF4A3B52.toInt(), 0xFF33544A.toInt(),
        0xFF544A33.toInt(), 0xFF35474E.toInt(), 0xFF4E3A3A.toInt()
    )

    fun submit(list: List<Book>, continuePosition: Int = -1) {
        // DiffUtil 精确刷新：从阅读页返回书架时只有进度/时间戳变了的卡重绑，
        // 封面和焦点动画状态不再整排重来
        val old = items
        val oldContinue = continuePos
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(oi: Int, ni: Int) = old[oi].id == list[ni].id
            // 「继续」角标不在 Book 字段里，位置变化也要算内容变化
            override fun areContentsTheSame(oi: Int, ni: Int) =
                old[oi] == list[ni] && (oi == oldContinue) == (ni == continuePosition)
        })
        items = list
        continuePos = continuePosition
        diff.dispatchUpdatesTo(this)
    }

    fun itemAt(position: Int): Book? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = items[position]

        // 复用复位：上个书留下的缩放/透明度状态不能带过来。
        // 恰好被重绑的 view 可能正持有焦点（删除后 refresh），那时保持选中态。
        val focused = holder.cardBox.isFocused
        holder.cardBox.scaleX = if (focused) SEL_SCALE else IDLE_SCALE
        holder.cardBox.scaleY = if (focused) SEL_SCALE else IDLE_SCALE
        holder.cardBox.alpha = if (focused) 1f else IDLE_ALPHA
        holder.cardBox.translationZ = if (focused) SEL_Z else 0f

        val pct = (b.progressPercent * 1000).toInt().coerceIn(0, 1000)
        holder.progress.progress = pct
        // 「继续」跟着最近在读的那本走，排序换了也不挂错卡
        holder.badgeContinue.visibility =
            if (position == continuePos && !b.finished && b.progressPercent < 1f) View.VISIBLE else View.GONE
        holder.badgeDone.visibility =
            if (b.finished || b.progressPercent >= 1f) View.VISIBLE else View.GONE

        bindCover(holder, b)

        holder.cardBox.setOnClickListener { onClick(b) }
        holder.cardBox.setOnLongClickListener {
            onLongClick(b)
            true
        }
        holder.cardBox.setOnFocusChangeListener { v, hasFocus ->
            // 110ms + 快起缓收：与逐张滚动的 ~100ms 节奏同步，又比 90ms 的"弹一下"顺
            v.animate().scaleX(if (hasFocus) SEL_SCALE else IDLE_SCALE)
                .scaleY(if (hasFocus) SEL_SCALE else IDLE_SCALE)
                .alpha(if (hasFocus) 1f else IDLE_ALPHA)
                .translationZ(if (hasFocus) SEL_Z else 0f)
                .setDuration(110L)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
            if (hasFocus) onFocus(b, position)
        }
    }

    private fun bindCover(holder: VH, b: Book) {
        val ctx = holder.cardBox.context
        val color = placeholderColors[(b.id.hashCode() and 0x7fffffff) % placeholderColors.size]
        holder.coverBox.backgroundTintList = ColorStateList.valueOf(color)

        // 先摆好占位（底色 + 书名首字），封面解码完再盖上去，避免滚动时闪空白
        holder.placeholder.text = b.title.trim().take(1).ifBlank { "书" }
        holder.placeholder.visibility = View.VISIBLE
        holder.cover.setImageDrawable(null)
        holder.cardBox.tag = b.id

        CoverLoader.load(ctx, b) { bmp ->
            // 网格复用：解码回来时这条 item 可能已经绑给别的书了
            if (holder.cardBox.tag != b.id) return@load
            if (bmp != null) {
                // 竖版封面按 fitCenter 完整显示，两侧会露出底色 —— 用中性深灰，
                // 别让占位那套彩色底透出来
                holder.coverBox.backgroundTintList = null
                holder.cover.setImageBitmap(bmp)
                holder.placeholder.visibility = View.GONE
            }
        }
    }

    companion object {
        // 选中/非选中的对比要拉满，3.5 寸屏上一眼看出焦点在哪本
        private const val SEL_SCALE = 1.1f
        private const val IDLE_SCALE = 0.9f
        private const val IDLE_ALPHA = 0.8f
        private const val SEL_Z = 8f
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        // 焦点/点击都挂在卡片本体上（itemView 是铺满列表高度的定位容器，不可聚焦）
        val cardBox: View = v.findViewById(R.id.cardBox)
        val progress: ProgressBar = v.findViewById(R.id.pbProgress)
        val coverBox: View = v.findViewById(R.id.coverBox)
        val cover: ImageView = v.findViewById(R.id.ivCover)
        val placeholder: TextView = v.findViewById(R.id.tvCoverPlaceholder)
        val badgeContinue: TextView = v.findViewById(R.id.tvBadgeContinue)
        val badgeDone: TextView = v.findViewById(R.id.tvBadgeDone)
    }
}
