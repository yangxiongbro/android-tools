package com.android_tools.app.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.android_tools.app.notify.Notify
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * 蓝牙自动同步：和 WiFi 同步一样，盯着同步文件夹，谁有变化就传给对方。
 *
 * 帧格式（与桌面端一致）：2 字节大端头部长度 + UTF-8 JSON 头 + 负载
 *  - {t:"hello", name}                握手
 *  - {t:"manifest", v, files:[{p,s,m}]}
 *  - {t:"want", p}                     请求文件
 *  - {t:"file", p, n, o, m} + 负载      分块传输
 */
class BtSyncEngine(
    private val engine: SyncEngine,
    private val onLog: (String) -> Unit,
    private val onState: (running: Boolean, device: String, sent: Int, received: Int) -> Unit,
    /** 传输进度（null 表示当前没有传输） */
    private val onProgress: (TransferInfo?) -> Unit = {},
) {
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CHUNK = 32 * 1024
        private const val TICK_MS = 1500L
        private const val MAX_BYTES = 100L * 1024 * 1024
    }

    private var scope: CoroutineScope? = null
    private var socket: BluetoothSocket? = null
    private var output: DataOutputStream? = null
    private var deviceName = ""
    private var sentCount = 0
    private var receivedCount = 0
    private var lastVersion = ""

    /** 已发出、还没收到回应的 want（防止反复请求） */
    private val requested = HashMap<String, Long>()
    /** 正在接收：path → 字节流 */
    private val incoming = HashMap<String, ByteArrayOutputStream>()
    /** 蓝牙自己正在传输什么（WiFi 引擎里没有这个状态，不能用 engine.state.transfer） */
    @Volatile
    private var currentTransfer: TransferInfo? = null
    /** 最近一次进度更新的时间（用于清理卡住的进度） */
    @Volatile
    private var lastProgressAt: Long = 0

    /** 对方取消过的文件：本次不再请求 */
    /** 取消登记表（本端 / 对方的取消都记这里，唯一真相来源） */
    /** 上次提示「重复请求」的时间（限频 10 秒） */
    private var lastDuplicateWantLogAt = 0L
    /** 上次提示「同步文件夹为空/读不到」的时间（限频 30 秒） */
    private var lastEmptyScanLogAt = 0L
    private val cancelled = TransferCancelRegistry()
    /** 刚收 / 发过的文件指纹（path -> 大小:修改时间），防止两端来回重传 */
    private val syncedAt = HashMap<String, String>()
    /** 正在发送的文件（避免同一条 want 并发发送） */
    private val sending = java.util.Collections.synchronizedSet(HashSet<String>())
    private val incomingMeta = HashMap<String, Pair<Long, Long>>()
    /** 刚收到的文件：path → (发送方 mtime, 落盘 mtime)。SAF 目录改不了 mtime，上报时用它顶替 */
    private val receivedStamps = HashMap<String, Pair<Long, Long>>()
    /** 开启同步那一刻的指纹：只有之后新增 / 改动的文件才参与同步 */
    private var baseline: MutableMap<String, String> = HashMap()

    val isRunning: Boolean get() = scope != null

    fun start(device: BluetoothDevice, adapter: BluetoothAdapter?) {
        stop()
        deviceName = runCatching { device.name }.getOrNull() ?: "蓝牙设备"
        sentCount = 0
        receivedCount = 0
        lastVersion = ""
        requested.clear()
        incoming.clear()
        incomingMeta.clear()
        // 载入「已取消」记录（跨会话）：内容没变的不再自动同步
        val localNow = engine.listSyncItems().associate { it.path to "${it.size}:${it.document.lastModified()}" }
        val keptRecords = CancelStore.load(engine.context())
            .filter { record -> localNow[record.path] == record.fingerprint }
        cancelled.clear()
        for (record in keptRecords) cancelled.mark(record.path, TransferCancelRegistry.Source.LOCAL)
        if (keptRecords.isNotEmpty()) {
            onLog("有 ${keptRecords.size} 个文件被取消过，本次不再同步（内容变化后会自动恢复）")
        }

        SyncKeepAliveService.start(engine.context())
        baseline = engine.listSyncItems().associate { item ->
            item.path to "${item.size}:${item.document.lastModified()}"
        }.toMutableMap()
        // 只有开启同步之后新增 / 改动的文件才会传给电脑
        onLog("本次同步只传输开始之后变化的文件（当前已有 ${baseline.size} 个，作为基线）")
        val current = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = current
        onState(true, deviceName, 0, 0)
        onLog("正在连接 $deviceName…")

        current.launch {
            var attempt = 0
            // 连不上就一直重试：常见情况是手机先开同步、电脑后开，这时必须能自己重连上去
            while (current.isActive) {
                var connected: BluetoothSocket? = null
                try {
                    runCatching { adapter?.cancelDiscovery() }
                    connected = connect(device)
                    socket = connected
                    output = DataOutputStream(connected.outputStream)
                    attempt = 0
                    onLog("已连接 $deviceName")
                    sendFrame(JSONObject().put("t", "hello").put("name", android.os.Build.MODEL ?: "安卓设备"))
                    refreshManifest(force = true)

                    // 收帧循环（阻塞读放在 IO 线程）
                    val input = DataInputStream(connected.inputStream)
                    // 有的设备「连上了」但其实对面没在监听：8 秒内收不到任何帧就当没连上，重连
                    val sawFrame = java.util.concurrent.atomic.AtomicBoolean(false)
                    val watchdog = current.launch {
                        delay(8000)
                        if (!sawFrame.get()) {
                            onLog("对方没有握手响应，重新连接…")
                            runCatching { connected.close() }
                        }
                    }
                    try {
                        while (current.isActive) {
                            val headerLength = input.readUnsignedShort()
                            if (headerLength <= 0 || headerLength > 64 * 1024) break
                            val headerBytes = ByteArray(headerLength)
                            input.readFully(headerBytes)
                            val header = JSONObject(String(headerBytes, Charsets.UTF_8))
                            val payloadSize = header.optLong("z", 0L)
                            val payload = if (payloadSize > 0) {
                                ByteArray(payloadSize.toInt()).also { input.readFully(it) }
                            } else {
                                ByteArray(0)
                            }
                            sawFrame.set(true)
                            handleFrame(header, payload)
                        }
                    } finally {
                        watchdog.cancel()
                    }
                    onLog("蓝牙连接已断开")
                } catch (error: Exception) {
                    onLog("蓝牙同步中断：${error.message ?: error.javaClass.simpleName}")
                } finally {
                    runCatching { connected?.close() }
                    socket = null
                    output = null
                }
                if (!current.isActive) break
                attempt += 1
                onLog("3 秒后重连 $deviceName（第 $attempt 次）")
                delay(3000)
            }
            if (scope === current) {
                scope = null
                runCatching { current.cancel() }
                runCatching { SyncKeepAliveService.stop(engine.context()) }
                onState(false, deviceName, sentCount, receivedCount)
            }
        }

        // 进度看门狗：6 秒没有新进度就清掉，避免界面卡在某个百分比
        current.launch {
            while (current.isActive) {
                delay(2000)
                if (currentTransfer != null && System.currentTimeMillis() - lastProgressAt > 6000) {
                    reportProgress(null)
                    runCatching { Notify.clearSync(engine.context()) }
                    onLog("传输已中断（长时间没有进度）")
                }
            }
        }

        // 定时检查本地清单变化（约每 10 秒强制发一次，避免版本号没变时对方漏掉新增文件）
        current.launch {
            var ticks = 0
            while (current.isActive) {
                delay(TICK_MS)
                ticks += 1
                runCatching { refreshManifest(force = ticks % 7 == 0) }
                    .onFailure { onLog("清单刷新失败：${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }

    fun stop() {
        runCatching { Notify.clearSync(engine.context()) }
        val current = scope ?: return
        runCatching { SyncKeepAliveService.stop(engine.context()) }
        scope = null
        runCatching { socket?.close() }
        socket = null
        output = null
        runCatching { current.cancel() }
        reportProgress(null)
        onState(false, deviceName, sentCount, receivedCount)
        onLog("蓝牙同步已停止")
    }

    /** 连接：标准 SPP → 通道 1 → 不加密 SPP，依次重试 */
    /**
     * 取消当前蓝牙传输（不断开连接）。
     *
     * 语义统一在这里：登记 → 丢掉本端半截数据 → 清进度 → 通知对方（skip + 清单 c）。
     * 返回 true 表示这是一次新的取消（界面据此只提示一次）。
     */
    fun cancelTransfer(): Boolean {
        val info = currentTransfer
        reportProgress(null)
        runCatching { Notify.clearSync(engine.context()) }
        if (info == null) {
            onLog("当前没有正在传输的文件")
            return false
        }
        val isNew = cancelled.mark(info.path, TransferCancelRegistry.Source.LOCAL)
        incoming.remove(info.path)
        requested.remove(info.path)
        val stamp = engine.listSyncItems().firstOrNull { it.path == info.path }
            ?.let { "${it.size}:${it.document.lastModified()}" } ?: "${info.total}:0"
        CancelStore.record(engine.context(), info.path, stamp)
        if (isNew) {
            // 不管收发都通知对方：接收端要让发送端停，发送端要让接收端清掉半截缓冲
            runCatching { sendFrame(JSONObject().put("t", "skip").put("p", info.path)) }
            onLog("已取消传输：${info.path}（本次同步不再重试）")
        }
        runCatching { refreshManifest(force = true) }
        return isNew
    }

    /** 统一出口：记下当前传输，再交给外面（通知栏 / 界面） */
    private fun reportProgress(info: TransferInfo?) {
        currentTransfer = info
        lastProgressAt = System.currentTimeMillis()
        if (info != null) {
            TransferHub.report(TransferHub.Kind.BT, info.path, info.outgoing, info.sent, info.total)
        } else {
            TransferHub.finish(TransferHub.Kind.BT)
        }
        onProgress(info)
    }

    /** 蓝牙传输也把进度放到通知栏 */
    private fun notifyProgress(info: TransferInfo) {
        if (info.total <= 0) return
        runCatching {
            Notify.syncProgress(
                context = engine.context(),
                title = if (info.outgoing) "正在发送 ${info.path}" else "正在接收 ${info.path}",
                text = "${info.percent}% · ${info.sent / 1024} KB / ${info.total / 1024} KB",
                percent = info.percent,
            )
        }
    }

    private fun connect(device: BluetoothDevice): BluetoothSocket {
        var lastError: Exception? = null

        fun attempt(factory: () -> BluetoothSocket): BluetoothSocket? {
            var candidate: BluetoothSocket? = null
            return try {
                candidate = factory()
                candidate.connect()
                candidate
            } catch (error: Exception) {
                lastError = error
                runCatching { candidate?.close() }
                null
            }
        }

        attempt { device.createRfcommSocketToServiceRecord(SPP_UUID) }?.let { return it }
        attempt {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, 1) as BluetoothSocket
        }?.let { return it }
        attempt { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }?.let { return it }
        throw lastError ?: java.io.IOException("无法连接蓝牙设备")
    }

    private fun sendFrame(header: JSONObject) {
        header.put("z", 0)
        val bytes = header.toString().toByteArray(Charsets.UTF_8)
        val stream = output ?: return
        synchronized(this) {
            runCatching {
                stream.writeShort(bytes.size)
                stream.write(bytes)
                stream.flush()
            }
        }
    }

    private fun sendFrame(header: JSONObject, payload: ByteArray) {
        // z = 本帧负载长度；n 仍然表示文件总大小
        header.put("z", payload.size)
        val bytes = header.toString().toByteArray(Charsets.UTF_8)
        val stream = output ?: return
        synchronized(this) {
            runCatching {
                stream.writeShort(bytes.size)
                stream.write(bytes)
                stream.write(payload)
                stream.flush()
            }
        }
    }

    private fun refreshManifest(force: Boolean) {
        val items = engine.listSyncItems()
        // 文件内容变了：解除它的取消记录，重新参与同步
        runCatching {
            val fingerprints = items.associate { it.path to "${it.size}:${it.document.lastModified()}" }
            val kept = CancelStore.reconcile(engine.context(), fingerprints)
            for (record in kept) {
                if (!cancelled.has(record.path)) cancelled.mark(record.path, TransferCancelRegistry.Source.LOCAL)
            }
        }
        if (items.isEmpty()) {
            val now = System.currentTimeMillis()
            if (now - lastEmptyScanLogAt > 30_000) {
                lastEmptyScanLogAt = now
                onLog(
                    "蓝牙同步：同步文件夹里没有可同步的文件（${engine.describeSyncTarget("")}）" +
                        "，若目录里明明有文件，请到设置里重新选择文件夹",
                )
            }
        }
        // SAF 目录写文件后改不了修改时间：刚收到的文件按发送方的 mtime 上报，
        // 否则「电脑发过来 → 手机报了个新时间 → 电脑又去拉回来」会来回同步。
        val changedItems = items.filter { item ->
            baseline[item.path] != "${item.size}:${item.document.lastModified()}"
        }
        fun reportedMtime(item: SyncEngine.LocalItem): Long {
            val disk = item.document.lastModified()
            val stamp = receivedStamps[item.path] ?: return disk
            return if (kotlin.math.abs(disk - stamp.second) <= 2000) stamp.first else disk
        }
        val version = manifestVersion(changedItems, ::reportedMtime)
        if (!force && version == lastVersion) return
        if (version != lastVersion) {
            // 让用户能看到「手机确实扫到了新文件」，而不是毫无反应
            val sample = changedItems.firstOrNull()?.path ?: ""
            onLog("清单已更新：${changedItems.size} 个文件待同步${if (sample.isEmpty()) "" else "（如 $sample）"}")
        }
        lastVersion = version
        val files = JSONArray()
        for (item in changedItems) {
            files.put(
                JSONObject()
                    .put("p", item.path)
                    .put("s", item.size)
                    .put("m", reportedMtime(item))
            )
        }
        val cancelledList = JSONArray()
        for (path in cancelled.localPaths()) cancelledList.put(path)
        val synced = JSONArray()
        for ((path, stamp) in syncedAt) {
            synced.put(JSONArray().put(path).put(stamp))
        }
        sendFrame(
            JSONObject()
                .put("t", "manifest")
                .put("v", version)
                .put("files", files)
                .put("c", cancelledList)
                .put("s", synced),
        )
    }

    private fun manifestVersion(items: List<SyncEngine.LocalItem>, mtimeOf: (SyncEngine.LocalItem) -> Long): String {
        val digest = MessageDigest.getInstance("SHA-1")
        for (item in items.sortedBy { it.path }) {
            digest.update("${item.path}:${item.size}:${mtimeOf(item)};".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun handleFrame(header: JSONObject, payload: ByteArray) {
        when (header.optString("t")) {
            "hello" -> {
                sendFrame(JSONObject().put("t", "manifest").put("v", lastVersion).put("files", JSONArray()))
                refreshManifest(force = true)
            }
            "manifest" -> {
                val syncedList = header.optJSONArray("s")
                if (syncedList != null) {
                    for (index in 0 until syncedList.length()) {
                        val pair = syncedList.optJSONArray(index) ?: continue
                        val path = pair.optString(0)
                        val stamp = pair.optString(1)
                        if (path.isNotEmpty() && stamp.isNotEmpty()) syncedAt[path] = stamp
                    }
                }
                val remoteCancelled = header.optJSONArray("c")
                if (remoteCancelled != null) {
                    val paths = buildList {
                        for (index in 0 until remoteCancelled.length()) {
                            remoteCancelled.optString(index).takeIf { it.isNotEmpty() }?.let { add(it) }
                        }
                    }
                    for (path in cancelled.mergeRemote(paths)) {
                        incoming.remove(path)
                        reportProgress(null)
                        runCatching { Notify.clearSync(engine.context()) }
                        onLog("对方取消了 $path，本次同步不再重试")
                    }
                }
                handleManifest(header.optJSONArray("files") ?: JSONArray())
            }
            "want" -> {
                // 关键：异步发送。同步调用会把收帧循环卡住，发送期间收不到对方的 skip / 清单
                val path = header.optString("p")
                if (path.isNotEmpty() && cancelled.has(path)) {
                    // 电脑还在要一个已经取消的文件：告诉它别再要了
                    runCatching { sendFrame(JSONObject().put("t", "skip").put("p", path)) }
                    onLog("电脑请求已取消的文件：$path（已忽略）")
                } else if (path.isNotEmpty() && !sending.add(path)) {
                    // 同一个文件还在发：忽略重复请求，但要留痕，避免看起来"没反应"
                    val now = System.currentTimeMillis()
                    if (now - lastDuplicateWantLogAt > 10_000) {
                        lastDuplicateWantLogAt = now
                        onLog("电脑重复请求 $path，正在发送中，已忽略")
                    }
                } else if (path.isNotEmpty()) {
                    onLog("开始发送 $path")
                    scope?.launch {
                        try {
                            handleWant(path)
                        } finally {
                            sending.remove(path)
                        }
                    }
                }
            }
            "skip" -> {
                // 对方取消了这个文件：本次不再请求，同时清掉可能存在的半截接收缓冲
                val path = header.optString("p")
                if (path.isNotEmpty()) {
                    val had = cancelled.has(path)
                    cancelled.mark(path, TransferCancelRegistry.Source.PEER)
                    incoming.remove(path)
                    reportProgress(null)
                    runCatching { Notify.clearSync(engine.context()) }
                    val reason = header.optString("r")
                    if (!had) {
                        onLog(
                            if (reason == "missing") "对方没有该文件：$path"
                            else "对方取消了 $path，本次同步不再重试",
                        )
                    }
                }
            }
            "file" -> handleFile(header, payload)
        }
    }

    private fun handleManifest(files: JSONArray) {
        val local = engine.listSyncItems().associateBy { it.path }
        val now = System.currentTimeMillis()
        for (index in 0 until files.length()) {
            val entry = files.optJSONObject(index) ?: continue
            val path = entry.optString("p")
            val size = entry.optLong("s", 0)
            val mtime = entry.optLong("m", 0)
            if (path.isEmpty()) continue
            if (incoming.containsKey(path)) continue
            if (cancelled.has(path)) continue
            val mine = local[path]
            if (mine != null) {
                val mineTime = mine.document.lastModified()
                if (mine.size == size && kotlin.math.abs(mineTime - mtime) <= 2000) continue
                // 刚和对方同步过同一份（指纹一致）→ 不再重复请求
                if (syncedAt[path] == "$size:$mtime") continue
                if (mineTime > mtime) continue
            }
            val pending = requested[path]
            if (pending != null && now - pending < 15000) continue
            requested[path] = now
            sendFrame(JSONObject().put("t", "want").put("p", path))
            onLog("请求 $path")
        }
    }

    private fun handleWant(path: String) {
        if (path.isEmpty()) return
        val item = engine.listSyncItems().firstOrNull { it.path == path }
        if (item == null) {
            // 让对方别等了：文件已经不在同步目录里
            runCatching { sendFrame(JSONObject().put("t", "skip").put("p", path).put("r", "missing")) }
            onLog("对方请求的 $path 不存在，已忽略")
            return
        }
        if (item.size > engine.maxFileBytes()) {
            onLog("$path 太大，已跳过")
            return
        }
        val bytes = engine.readSyncItem(item) ?: return
        val mtime = item.document.lastModified()
        var offset = 0
        if (bytes.isEmpty()) {
            sendFrame(JSONObject().put("t", "file").put("p", path).put("n", 0).put("o", 0).put("m", mtime))
        }
        while (offset < bytes.size) {
            if (cancelled.has(path)) {
                // 对方取消了这个文件：停发并清进度，避免它那边还挂着进度条
                reportProgress(null)
                runCatching { Notify.clearSync(engine.context()) }
                onLog("对方取消了 $path，已停止发送")
                return
            }
            val length = minOf(CHUNK, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + length)
            sendFrame(
                JSONObject()
                    .put("t", "file")
                    .put("p", path)
                    .put("n", bytes.size)
                    .put("o", offset)
                    .put("m", mtime),
                chunk
            )
            offset += length
            val info = TransferInfo(path, offset.toLong(), bytes.size.toLong(), outgoing = true)
            reportProgress(info)
            notifyProgress(info)
            if (cancelled.has(path)) {
                // 本端或对方取消了这个文件：停发并清进度（cancelTransfer / skip 已记过日志）
                reportProgress(null)
                runCatching { Notify.clearSync(engine.context()) }
                return
            }
        }
        sentCount += 1
        syncedAt[path] = "${bytes.size}:$mtime"
        // 基线用「磁盘上的大小:修改时间」，和 refreshManifest 的比较口径保持一致
        engine.listSyncItems().firstOrNull { it.path == path }?.let { local ->
            baseline[path] = "${local.size}:${local.document.lastModified()}"
        }
        reportProgress(null)
        runCatching { Notify.clearSync(engine.context()) }
        onLog("已发送 $path（${bytes.size / 1024} KB）")
        onState(true, deviceName, sentCount, receivedCount)
    }

    private fun handleFile(header: JSONObject, payload: ByteArray) {
        val path = header.optString("p")
        val size = header.optLong("n", 0)
        val offset = header.optLong("o", 0)
        val mtime = header.optLong("m", 0)
        if (path.isEmpty() || size > MAX_BYTES) return
        // 本端取消过、或对方发了 skip：后续分块直接丢掉，不要再"复活"进度
        if (cancelled.has(path)) {
            incoming.remove(path)
            return
        }

        var buffer = incoming[path]
        if (buffer == null) {
            buffer = ByteArrayOutputStream()
            incoming[path] = buffer
            incomingMeta[path] = size to mtime
        }
        if (offset == 0L && buffer.size() > 0) {
            buffer.reset()
        }
        buffer.write(payload)
        val info = TransferInfo(path, buffer.size().toLong(), size, outgoing = false)
        reportProgress(info)
        notifyProgress(info)

        if (buffer.size().toLong() >= size) {
            val bytes = buffer.toByteArray()
            incoming.remove(path)
            val meta = incomingMeta.remove(path)
            requested.remove(path)
            if (engine.writeSyncFile(path, bytes)) {
                meta?.second?.takeIf { it > 0 }?.let { engine.touchSyncFile(path, it) }
                val localItem = engine.listSyncItems().firstOrNull { it.path == path }
                val disk = localItem?.document?.lastModified() ?: 0L
                meta?.second?.takeIf { it > 0 }?.let { receivedStamps[path] = it to disk }
                if (localItem != null) baseline[path] = "${localItem.size}:${disk}"
                receivedCount += 1
                meta?.second?.takeIf { it > 0 }?.let { syncedAt[path] = "${bytes.size}:$it" }
                reportProgress(null)
                runCatching { Notify.clearSync(engine.context()) }
                // 日志里带上落盘路径，出问题时一眼能看出存到哪了
                onLog("收到 $path（${bytes.size / 1024} KB）→ ${engine.describeSyncTarget(path)}")
                onState(true, deviceName, sentCount, receivedCount)
                runCatching { refreshManifest(force = true) }
            } else {
                onLog("写入失败：$path")
            }
        }
    }
}


