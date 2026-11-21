package com.screenrecorder.app.sync

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
    private val incomingMeta = HashMap<String, Pair<Long, Long>>()
    /** 刚收到的文件：path → (发送方 mtime, 落盘 mtime)。SAF 目录改不了 mtime，上报时用它顶替 */
    private val receivedStamps = HashMap<String, Pair<Long, Long>>()

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

        SyncKeepAliveService.start(engine.context())
        val current = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = current
        onState(true, deviceName, 0, 0)
        onLog("正在连接 $deviceName…")

        current.launch {
            var connected: BluetoothSocket? = null
            try {
                runCatching { adapter?.cancelDiscovery() }
                connected = connect(device)
                socket = connected
                output = DataOutputStream(connected.outputStream)
                onLog("已连接 $deviceName")
                sendFrame(JSONObject().put("t", "hello").put("name", android.os.Build.MODEL ?: "安卓设备"))
                refreshManifest(force = true)

                // 收帧循环（阻塞读放在 IO 线程）
                val input = DataInputStream(connected.inputStream)
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
                    handleFrame(header, payload)
                }
                onLog("蓝牙连接已断开")
            } catch (error: Exception) {
                onLog("蓝牙同步中断：${error.message ?: error.javaClass.simpleName}")
            } finally {
                runCatching { connected?.close() }
                socket = null
                output = null
                if (scope === current) {
                    scope = null
                    runCatching { current.cancel() }
                    runCatching { SyncKeepAliveService.stop(engine.context()) }
                    onState(false, deviceName, sentCount, receivedCount)
                }
            }
        }

        // 定时检查本地清单变化
        current.launch {
            while (current.isActive) {
                delay(TICK_MS)
                runCatching { refreshManifest(force = false) }
            }
        }
    }

    fun stop() {
        val current = scope ?: return
        runCatching { SyncKeepAliveService.stop(engine.context()) }
        scope = null
        runCatching { socket?.close() }
        socket = null
        output = null
        runCatching { current.cancel() }
        onState(false, deviceName, sentCount, receivedCount)
        onLog("蓝牙同步已停止")
    }

    /** 连接：标准 SPP → 通道 1 → 不加密 SPP，依次重试 */
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
        // SAF 目录写文件后改不了修改时间：刚收到的文件按发送方的 mtime 上报，
        // 否则「电脑发过来 → 手机报了个新时间 → 电脑又去拉回来」会来回同步。
        fun reportedMtime(item: SyncEngine.LocalItem): Long {
            val disk = item.document.lastModified()
            val stamp = receivedStamps[item.path] ?: return disk
            return if (kotlin.math.abs(disk - stamp.second) <= 2000) stamp.first else disk
        }
        val version = manifestVersion(items, ::reportedMtime)
        if (!force && version == lastVersion) return
        lastVersion = version
        val files = JSONArray()
        for (item in items) {
            files.put(
                JSONObject()
                    .put("p", item.path)
                    .put("s", item.size)
                    .put("m", reportedMtime(item))
            )
        }
        sendFrame(JSONObject().put("t", "manifest").put("v", version).put("files", files))
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
            "manifest" -> handleManifest(header.optJSONArray("files") ?: JSONArray())
            "want" -> handleWant(header.optString("p"))
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
            val mine = local[path]
            if (mine != null) {
                val mineTime = mine.document.lastModified()
                if (mine.size == size && kotlin.math.abs(mineTime - mtime) <= 2000) continue
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
        val item = engine.listSyncItems().firstOrNull { it.path == path } ?: return
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
        }
        sentCount += 1
        onLog("已发送 $path（${bytes.size / 1024} KB）")
        onState(true, deviceName, sentCount, receivedCount)
    }

    private fun handleFile(header: JSONObject, payload: ByteArray) {
        val path = header.optString("p")
        val size = header.optLong("n", 0)
        val offset = header.optLong("o", 0)
        val mtime = header.optLong("m", 0)
        if (path.isEmpty() || size > MAX_BYTES) return

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

        if (buffer.size().toLong() >= size) {
            val bytes = buffer.toByteArray()
            incoming.remove(path)
            val meta = incomingMeta.remove(path)
            requested.remove(path)
            if (engine.writeSyncFile(path, bytes)) {
                meta?.second?.takeIf { it > 0 }?.let { engine.touchSyncFile(path, it) }
                val disk = engine.listSyncItems().firstOrNull { it.path == path }?.document?.lastModified() ?: 0L
                meta?.second?.takeIf { it > 0 }?.let { receivedStamps[path] = it to disk }
                receivedCount += 1
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
