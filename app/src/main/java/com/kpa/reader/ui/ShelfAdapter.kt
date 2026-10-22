package com.kpa.reader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
            if (b.author.isNotBlank()) {
                append(if (b.author.length > 10) b.author.take(10) + "…" else b.author)
                append(" · ")
            }
            append(b.format.uppercase())
            append(" · ")
            append(b.chapters.size)
            append("章 · ")
            append(pct)
            append("% · ")
            append(whenRead)
        }

        holder.itemView.setOnClickListener { onClick(b) }
        holder.itemView.setOnLongClickListener {
            onLongClick(b)
            true
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val progress: ProgressBar = v.findViewById(R.id.pbProgress)
        val meta: TextView = v.findViewById(R.id.tvMeta)
    }
}
