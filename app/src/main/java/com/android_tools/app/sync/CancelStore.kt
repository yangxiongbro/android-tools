package com.android_tools.app.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「已取消」记录（跨会话保留，和桌面端 `sync.json` 里的 `cancelled` 一致）。
 *
 * 语义：
 *  - 用户取消过的文件不再自动同步（重启 App 也一样）；
 *  - 记录里存了取消时的「大小:修改时间」，文件内容一变就自动解除；
 *  - 用户可以在「文件同步设置」里一键清空并恢复同步。
 */
object CancelStore {
    data class Record(val path: String, val fingerprint: String, val at: Long)

    private const val PREFS = "file-sync"
    private const val KEY = "cancelled"
    private const val LIMIT = 500

    fun load(context: Context): List<Record> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val path = item.optString("path")
                    if (path.isEmpty()) continue
                    add(Record(path, item.optString("fingerprint"), item.optLong("at")))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, records: List<Record>) {
        val array = JSONArray()
        for (record in records.take(LIMIT)) {
            array.put(
                JSONObject()
                    .put("path", record.path)
                    .put("fingerprint", record.fingerprint)
                    .put("at", record.at),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    /** 记一条：同一个文件只保留最新一条 */
    fun record(context: Context, path: String, fingerprint: String) {
        if (path.isEmpty()) return
        val next = load(context).filterNot { it.path == path }
            .toMutableList()
        next.add(0, Record(path, fingerprint, System.currentTimeMillis()))
        save(context, next)
    }

    /** 清空（设置里「恢复同步」） */
    fun clear(context: Context): Int {
        val count = load(context).size
        save(context, emptyList())
        return count
    }

    /**
     * 和当前目录比对：指纹变了 / 文件没了的记录自动解除。
     * 返回仍然有效的记录（调用方据此重建「不再同步」集合）。
     */
    fun reconcile(context: Context, current: Map<String, String>): List<Record> {
        val records = load(context)
        if (records.isEmpty()) return records
        val kept = records.filter { record ->
            val now = current[record.path]
            // 本地没有这个文件（例如是从电脑下载被取消的）→ 保留记录，否则每次轮询都会重试
            now == null || now == record.fingerprint
        }
        if (kept.size != records.size) save(context, kept)
        return kept
    }

    fun count(context: Context): Int = load(context).size
}
