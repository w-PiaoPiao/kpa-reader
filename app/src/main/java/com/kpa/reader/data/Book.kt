package com.kpa.reader.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 章节（或 EPUB 中的 spine 项）。
 * start/end 是全书字符区间；blockStart/blockEnd 是块区间。
 * 纯图片章节（封面）没有字符，只能靠块区间定位，所以两者都要存。
 */
data class Chapter(
    val title: String,
    val start: Int,
    val end: Int,
    val blockStart: Int = -1,
    val blockEnd: Int = -1
) {
    val length: Int get() = (end - start).coerceAtLeast(0)
}

/** 书签：记录全书字符偏移 */
data class Bookmark(
    val offset: Int,
    val preview: String,
    val createdAt: Long
)

data class Book(
    val id: String,
    val title: String,
    val uri: String,
    val format: String,          // "txt" | "epub" | "mobi" | "azw3"
    val encoding: String,
    val charCount: Int,
    val chapters: List<Chapter>,
    val progressOffset: Int,     // 全书字符偏移
    val progressPercent: Float,  // 0f..1f
    val addedAt: Long,
    val lastReadAt: Long,
    val bookmarks: List<Bookmark> = emptyList(),
    val finished: Boolean = false,
    val author: String = "",
    val hasImages: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("uri", uri)
        put("format", format)
        put("encoding", encoding)
        put("charCount", charCount)
        put("progressOffset", progressOffset)
        put("progressPercent", progressPercent.toDouble())
        put("addedAt", addedAt)
        put("lastReadAt", lastReadAt)
        put("finished", finished)
        put("chapters", JSONArray().apply {
            chapters.forEach {
                put(JSONObject().apply {
                    put("t", it.title); put("s", it.start); put("e", it.end)
                    put("bs", it.blockStart); put("be", it.blockEnd)
                })
            }
        })
        put("bookmarks", JSONArray().apply {
            bookmarks.forEach {
                put(JSONObject().apply {
                    put("o", it.offset); put("p", it.preview); put("c", it.createdAt)
                })
            }
        })
        put("author", author)
        put("hasImages", hasImages)
    }

    companion object {
        fun fromJson(o: JSONObject): Book {
            val chapters = ArrayList<Chapter>()
            o.optJSONArray("chapters")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    chapters.add(
                        Chapter(
                            c.optString("t"), c.optInt("s"), c.optInt("e"),
                            c.optInt("bs", -1), c.optInt("be", -1)
                        )
                    )
                }
            }
            val marks = ArrayList<Bookmark>()
            o.optJSONArray("bookmarks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    marks.add(Bookmark(c.optInt("o"), c.optString("p"), c.optLong("c")))
                }
            }
            return Book(
                id = o.optString("id"),
                title = o.optString("title"),
                uri = o.optString("uri"),
                format = o.optString("format", "txt"),
                encoding = o.optString("encoding", "UTF-8"),
                charCount = o.optInt("charCount"),
                chapters = chapters,
                progressOffset = o.optInt("progressOffset"),
                progressPercent = o.optDouble("progressPercent", 0.0).toFloat(),
                addedAt = o.optLong("addedAt"),
                lastReadAt = o.optLong("lastReadAt"),
                bookmarks = marks,
                finished = o.optBoolean("finished"),
                author = o.optString("author", ""),
                hasImages = o.optBoolean("hasImages", false)
            )
        }
    }
}
