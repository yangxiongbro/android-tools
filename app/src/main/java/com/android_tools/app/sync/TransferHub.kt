package com.android_tools.app.sync

/**
 * 传输中枢：WiFi 与蓝牙共用一套「当前传输 + 取消」状态。
 *
 * 以前两个引擎各自维护 currentTransfer，界面只能按当前 Tab 决定取消谁，
 * 容易出现「点取消没反应」或在错误通道上取消。现在：
 *   - 任何通道开始传输都 `report()` 到这里，同一时刻只有一个当前传输；
 *   - 取消只问中枢，它知道这次是哪个通道、哪个文件；
 *   - 取消登记表也放在这里，两个引擎都查同一份。
 */
object TransferHub {
    enum class Kind { WIFI, BT }

    data class Active(
        val kind: Kind,
        val path: String,
        val outgoing: Boolean,
        val sent: Long,
        val total: Long,
        val at: Long = System.currentTimeMillis(),
    )

    private val registry = TransferCancelRegistry()
    private val handlers = LinkedHashMap<Kind, () -> Boolean>()
    @Volatile
    private var active: Active? = null

    /** 各通道注册自己的取消实现（停传输 + 通知对端） */
    fun register(kind: Kind, handler: () -> Boolean) {
        handlers[kind] = handler
    }

    fun report(kind: Kind, path: String, outgoing: Boolean, sent: Long, total: Long) {
        if (path.isEmpty()) return
        active = Active(kind, path, outgoing, sent, total)
    }

    fun finish(kind: Kind, path: String? = null) {
        val current = active ?: return
        if (current.kind != kind) return
        if (path != null && current.path != path) return
        active = null
    }

    fun current(): Active? = active

    /** 取消当前传输：界面按钮统一走这里 */
    fun cancel(): Boolean {
        val current = active
        if (current != null) {
            registry.mark(current.path, TransferCancelRegistry.Source.LOCAL)
            active = null
            return handlers[current.kind]?.invoke() ?: false
        }
        // 兜底：没有登记当前传输时按通道顺序试一次
        for (kind in listOf(Kind.WIFI, Kind.BT)) {
            if (handlers[kind]?.invoke() == true) return true
        }
        return false
    }

    fun isCancelled(path: String): Boolean = registry.has(path)

    fun markCancelled(path: String, source: TransferCancelRegistry.Source): Boolean =
        registry.mark(path, source)

    fun cancelledPaths(): TransferCancelRegistry = registry

    /** 开始新一轮同步：清空登记与当前传输 */
    fun reset() {
        registry.clear()
        active = null
    }
}
