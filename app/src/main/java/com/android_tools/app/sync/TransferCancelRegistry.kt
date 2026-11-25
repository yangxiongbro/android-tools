package com.android_tools.app.sync

/**
 * 传输取消登记表 —— 取消状态的唯一来源。
 *
 * 语义（和桌面端 `btCancel.ts` 保持一致）：
 *  - 一次同步会话内，任意一端取消某个文件，该文件就不再传输（双向）；
 *  - 区分「谁取消的」只用于日志文案，不影响判定；
 *  - 对方取消通过 skip 帧 + 清单 `c` 字段两条通道同步过来，都合并进这里。
 *
 * 之前这里是一个裸 HashSet，取消来源、原因、去重全靠调用点各自判断，
 * 出现了「取消后新文件也被取消」「两端状态不一致」等问题，现在统一收敛。
 */
class TransferCancelRegistry {
    enum class Source { LOCAL, PEER }

    private data class Entry(val path: String, val source: Source, val reason: String?, val at: Long)

    private val entries = LinkedHashMap<String, Entry>()

    /** 登记取消，返回 true 表示这是新登记（用于日志去重） */
    fun mark(path: String, source: Source, reason: String? = null): Boolean {
        if (path.isEmpty()) return false
        val existing = entries[path]
        if (existing != null) {
            // 本端取消优先：避免自己的操作被当成「对方取消」
            if (source == Source.LOCAL && existing.source == Source.PEER) {
                entries[path] = Entry(path, source, reason, System.currentTimeMillis())
            }
            return false
        }
        entries[path] = Entry(path, source, reason, System.currentTimeMillis())
        return true
    }

    fun has(path: String): Boolean = entries.containsKey(path)

    /** 本端取消的路径（写进清单 c 字段，保证对方一定能收到） */
    fun localPaths(): List<String> =
        entries.values.filter { it.source == Source.LOCAL }.map { it.path }

    fun reasonOf(path: String): String? = entries[path]?.reason

    /** 合并对方清单里的取消列表，返回新登记的路径 */
    fun mergeRemote(paths: List<String>): List<String> =
        paths.filter { mark(it, Source.PEER) }

    fun clear() = entries.clear()

    val size: Int get() = entries.size

    companion object {
        const val REASON_MISSING = "missing"
    }
}
