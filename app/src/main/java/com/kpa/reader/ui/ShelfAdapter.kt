package com.kpa.reader.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kpa.reader.R
import com.kpa.reader.data.Book
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShelfAdapter(
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit
) : RecyclerView.Adapter<ShelfAdapter.VH>() {

    private var items: List<Book> = emptyList()
    private val dateFmt = SimpleDateFormat("MM-dd", Locale.getDefault())

    /** 封面占位底色：按书 id 稳定取一个，不同书不会都是同一个灰块 */
    private val placeholderColors = intArrayOf(
        0xFF36485C.toInt(), 0xFF4A3B52.toInt(), 0xFF33544A.toInt(),
        0xFF544A33.toInt(), 0xFF35474E.toInt(), 0xFF4E3A3A.toInt()
    )

    fun submit(list: List<Book>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = items[position]
        holder.title.text = b.title

        val pct = (b.progressPercent * 100).toInt().coerceIn(0, 100)
        holder.progress.progress = (b.progressPercent * 1000).toInt().coerceIn(0, 1000)

        val whenRead = if (b.lastReadAt > 0) dateFmt.format(Date(b.lastReadAt)) else "—"
        holder.meta.text = buildString {
            append(b.format.uppercase())
            append(" · ")
            append(pct)
            append("% · ")
            append(whenRead)
        }

        bindCover(holder, b)

        holder.itemView.setOnClickListener { onClick(b) }
        holder.itemView.setOnLongClickListener {
            onLongClick(b)
            true
        }
    }

    private fun bindCover(holder: VH, b: Book) {
        val ctx = holder.itemView.context
        val color = placeholderColors[(b.id.hashCode() and 0x7fffffff) % placeholderColors.size]
        holder.coverBox.backgroundTintList = ColorStateList.valueOf(color)

        // 先摆好占位（底色 + 书名首字），封面解码完再盖上去，避免滚动时闪空白
        holder.placeholder.text = b.title.trim().take(1).ifBlank { "书" }
        holder.placeholder.visibility = View.VISIBLE
        holder.cover.setImageDrawable(null)
        holder.itemView.tag = b.id

        CoverLoader.load(ctx, b) { bmp ->
            // 网格复用：解码回来时这条 item 可能已经绑给别的书了
            if (holder.itemView.tag != b.id) return@load
            if (bmp != null) {
                // 竖版封面按 fitCenter 完整显示，两侧会露出底色 —— 用中性深灰，
                // 别让占位那套彩色底透出来
                holder.coverBox.backgroundTintList = null
                holder.cover.setImageBitmap(bmp)
                holder.placeholder.visibility = View.GONE
            }
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val progress: ProgressBar = v.findViewById(R.id.pbProgress)
        val meta: TextView = v.findViewById(R.id.tvMeta)
        val coverBox: View = v.findViewById(R.id.coverBox)
        val cover: ImageView = v.findViewById(R.id.ivCover)
        val placeholder: TextView = v.findViewById(R.id.tvCoverPlaceholder)
    }
}
