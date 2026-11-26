package com.android_tools.app.sync

import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import com.android_tools.app.notify.Notify
import kotlinx.coroutines.launch
import java.util.UUID

/** 正在传输的文件（用于显示进度百分比） */
data class TransferInfo(
    val path: String,
    val sent: Long,
    val total: Long,
    val outgoing: Boolean,
) {
    /**
     * 进度百分比。
     *
     * 注意**上限是 99**：字节写进 socket 缓冲区不代表对方收到了 ——
     * 电脑端取消时手机这边往往已经把全部字节写完，如果这里给到 100%，
     * 界面就会先闪一下"传输完成"再跳回空闲。真正的"完成"以对方响应为准（记一条同步记录）。
     */
    val percent: Int
        get() = if (total <= 0) 0 else ((sent * 100) / total).toInt().coerceIn(0, 99)
}

/** 同步界面用到的状态。 */
data class SyncUiState(
    val address: String = "",
    val folderUri: String = "",
    val running: Boolean = false,
    val connected: Boolean = false,
    val lastError: String = "",
    val remoteVersion: String = "",
    val remoteCount: Int = 0,
    val downloaded: Int = 0,
    val uploaded: Int = 0,
    val logs: List<SyncLogEntry> = emptyList(),
    /** 单个文件大小上限（MB），默认 100 */
    val maxFileMb: Int = 100,
    /** 正在传输的文件与进度（WiFi / 蓝牙共用一块显示） */
    val transfer: TransferInfo? = null,
) {
    /** 没有用 SAF 选目录时，用应用自己的同步目录，开箱即用 */
    val folderReady: Boolean = true
}

/**
 * 文件同步引擎：按清单差异双向同步。
 *
 * - 桌面端把「同步文件夹」的清单开在 `/sync/list` 上，并用 version 标记变化；
 * - 本引擎每 3 秒拉一次清单：远端有新增 / 变更的文件就下载到手机同步文件夹；
 * - 本地文件夹里出现新文件（或大小变化）就上传给桌面端；
 * - 只处理 64 MB 以内的普通文件，跳过隐藏文件与临时文件。
 */
class SyncEngine(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(
        SyncUiState(
            address = prefs.getString(KEY_ADDRESS, "") ?: "",
            folderUri = prefs.getString(KEY_FOLDER, "") ?: "",
        )
    )
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private var job: Job? = null
    /** 用户取消过的文件；本次同步内不再自动重试，重新点「开始同步」会清空 */
    private val cancelledPaths = HashSet<String>()

    /** 点「取消」后置位，正在传输的文件会被中止，当前这一轮也不再继续 */
    @Volatile
    private var cancelRequested = false
    private var client: SyncClient? = null

    /** 远端文件表：path → 上次同步时的 size/mtime，用来判断「远端变了没有」 */
    private val remoteSnapshot = HashMap<String, RemoteFile>()

    /** 已经处理过的本地文件：path:size，避免重复上传 */
    private val uploadedKeys = HashSet<String>()
    /** 上次提示「没有可同步文件」的时间（限频 30 秒） */
    private var lastScanLogAt = 0L
    /** 开启同步那一刻的指纹：只有之后新增 / 改动的文件才上传 */
    private var baseline: Map<String, String>? = null

    private val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = runCatching {
                Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty().ifEmpty { UUID.randomUUID().toString() }
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            id
        }
    }

    private val deviceName: String by lazy {
        val model = runCatching { android.os.Build.MODEL }.getOrNull().orEmpty()
        if (model.isBlank()) "Android 设备" else model
    }

    fun updateAddress(address: String) {
        prefs.edit().putString(KEY_ADDRESS, address).apply()
        _state.value = _state.value.copy(address = address)
    }

    fun updateFolder(uri: Uri?) {
        val text = uri?.toString().orEmpty()
        prefs.edit().putString(KEY_FOLDER, text).apply()
        _state.value = _state.value.copy(folderUri = text)
        log(if (text.isEmpty()) "已恢复使用应用默认目录" else "同步目录已切换：${syncFolderLabel()}")
    }

    /** 已取消（不再自动同步）的文件数量 */
    fun cancelledCount(): Int = CancelStore.count(appContext)

    /** 清空已取消记录，恢复这些文件的同步 */
    fun clearCancelled(): Int {
        val count = CancelStore.clear(appContext)
        cancelledPaths.clear()
        TransferHub.cancelledPaths().clear()
        return count
    }

    /** 给蓝牙同步用：拿应用上下文起前台服务 */
    fun context(): Context = appContext

    /** 取消正在进行的传输（当前文件中断，这一轮剩下的文件也不再传） */
    /**
     * 取消当前 WiFi 传输。语义与蓝牙一致：
     * 登记 → 停当前传输 → 通知对端（对端下次拉取会收到 409）→ 记一条日志。
     * 返回 true 表示确实取消了一次传输。
     */
    fun cancelTransfer(): Boolean {
        // 先记下正在传的是什么，再清状态（清完 transfer 就没了）
        val info = _state.value.transfer ?: TransferHub.current()?.let {
            TransferInfo(it.path, it.sent, it.total, it.outgoing)
        }
        setTransfer(null)
        if (info == null) {
            // 没有正在传输的文件：点一下只是提示，不留下标记去打断下一次
            log("当前没有正在传输的文件")
            return false
        }
        cancelRequested = true
        cancelledPaths.add(info.path)
        TransferHub.markCancelled(info.path, TransferCancelRegistry.Source.LOCAL)
        // 持久化：重启 App 后也不要自动再传，文件内容变了才恢复。
        // 指纹必须来自真实文件；拿不到就用粘性标记，绝不能写 total:0
        //（否则下一次 reconcile 会认为"文件变了"，把记录解除，于是又自动传一遍）
        val realStamp = collectLocal().firstOrNull { it.path == info.path }
            ?.let { "${it.size}:${it.document.lastModified()}" }
            ?: CancelStore.STICKY
        CancelStore.record(appContext, info.path, realStamp)
        log("已取消传输：${info.path}（本次同步不再重试）")
        return true
    }

    /** 更新传输状态，同时把进度同步到通知栏 */
    private fun setTransfer(info: TransferInfo?) {
        _state.value = _state.value.copy(transfer = info)
        // 统一报到中枢：界面上的「取消」针对这里记录的当前传输
        if (info != null) {
            TransferHub.report(TransferHub.Kind.WIFI, info.path, info.outgoing, info.sent, info.total)
        } else {
            TransferHub.finish(TransferHub.Kind.WIFI)
        }
        if (info == null || info.total <= 0) {
            Notify.clearSync(appContext)
        } else {
            Notify.syncProgress(
                context = appContext,
                title = if (info.outgoing) "正在发送 ${info.path}" else "正在接收 ${info.path}",
                text = "${info.percent}% · ${info.sent / 1024} KB / ${info.total / 1024} KB",
                percent = info.percent,
            )
        }
    }

    /** 单个文件上限（MB） */
    fun maxFileMb(): Int = _state.value.maxFileMb.coerceIn(1, 4096)

    /** 单个文件上限（字节） */
    fun maxFileBytes(): Long = _state.value.maxFileMb.coerceIn(1, 4096).toLong() * 1024 * 1024

    fun updateMaxFileMb(mb: Int) {
        val fixed = mb.coerceIn(1, 4096)
        prefs.edit().putInt(KEY_MAX_MB, fixed).apply()
        _state.value = _state.value.copy(maxFileMb = fixed)
        log("单个文件上限已设为 $fixed MB")
    }

    /** 记录一条同步日志（界面上的「同步记录」里能看到） */
    fun note(text: String) {
        log(text)
    }

    /** 系统里已授权、但当前没用上的目录（有些机型从文件选择器返回时会丢回调，用它兜底） */
    fun grantedFolders(): List<String> {
        val current = _state.value.folderUri
        return runCatching {
            appContext.contentResolver.persistedUriPermissions
                .asSequence()
                .filter { it.isReadPermission || it.isWritePermission }
                .map { it.uri.toString() }
                .filter { it.startsWith("content://") && it != current }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())
    }

    /** 开始（或重启）同步循环 */
    fun start() {
        val address = _state.value.address.trim()
        if (address.isEmpty()) {
            log("请先填写电脑上的同步地址，例如 192.168.1.8:45872")
            return
        }
        // 地址 OK 再拉前台服务，避免启动失败后留下常驻通知
        SyncKeepAliveService.start(appContext)
        if (!_state.value.folderReady) {
            log("请先选择手机上的同步文件夹")
            return
        }
        // 开启同步时打基线：此前的文件视为已同步，不再整目录上传
        val localItems = collectLocal()
        baseline = localItems.associate { it.path to "${it.size}:${it.document.lastModified()}" }
        // 载入「已取消」记录（跨会话）：文件内容没变的不再自动同步
        val fingerprints = localItems.associate { it.path to "${it.size}:${it.document.lastModified()}" }
        val kept = CancelStore.reconcile(appContext, fingerprints)
        cancelledPaths.clear()
        for (record in kept) cancelledPaths.add(record.path)
        if (kept.isNotEmpty()) log("有 ${kept.size} 个文件被取消过，本次不再同步（内容变化后会自动恢复）")
        client = SyncClient(address)
        job?.cancel()
        _state.value = _state.value.copy(running = true, lastError = "")
        log("开始同步：$address")
        job = scope.launch { runLoop() }
    }

    fun stop() {
        SyncKeepAliveService.stop(appContext)
        job?.cancel()
        job = null
        baseline = null
        cancelledPaths.clear()
        setTransfer(null)
        _state.value = _state.value.copy(running = false, connected = false)
        log("已停止同步")
    }

    fun shutdown() {
        scope.cancel()
    }

    private suspend fun runLoop() {
        val syncClient = client ?: return
        var attempt = 0
        while (scope.isActive) {
            try {
                if (attempt == 0 || attempt % 20 == 0) {
                    val welcomed = syncClient.hello(deviceId, deviceName)
                    _state.value = _state.value.copy(connected = welcomed, lastError = if (welcomed) "" else "连不上电脑，请检查地址与 WiFi")
                    if (!welcomed) log("握手失败：${_state.value.address}")
                }
                val manifest = syncClient.list()
                if (manifest == null) {
                    _state.value = _state.value.copy(connected = false, lastError = "拉取清单失败")
                    attempt += 1
                    delay(INTERVAL_MS)
                    continue
                }
                _state.value = _state.value.copy(
                    connected = true,
                    lastError = "",
                    remoteVersion = manifest.version,
                    remoteCount = manifest.files.size,
                )
                pullChanged(syncClient, manifest)
                pushLocal(syncClient, manifest)
            } catch (error: Exception) {
                _state.value = _state.value.copy(lastError = error.message ?: "同步失败")
            }
            attempt += 1
            delay(INTERVAL_MS)
        }
    }

    /** 远端 → 手机 */
    private fun pullChanged(syncClient: SyncClient, manifest: RemoteManifest) {
        try {
            pullFiles(syncClient, manifest)
        } finally {
            // 不管成功、取消、还是连接断了，都不能让界面停在进度条上
            setTransfer(null)
            cancelRequested = false
        }
    }

    private fun pullFiles(syncClient: SyncClient, manifest: RemoteManifest) {
        for (file in manifest.files) {
            val known = remoteSnapshot[file.path]
            if (known != null && known.size == file.size && known.mtime == file.mtime) continue
            if (file.path in cancelledPaths) continue
            cancelRequested = false
            val bytes = syncClient.download(
                file.path,
                { sent, total ->
                    if (!cancelRequested && file.path !in cancelledPaths) {
                        setTransfer(TransferInfo(file.path, sent, total, outgoing = false))
                    }
                },
                { cancelRequested || file.path in cancelledPaths },
                {
                    cancelledPaths.add(file.path)
                    // 持久化（用远端指纹）：否则下一轮清单又会把它当成"要下载的文件"，
                    // 每 3 秒刷一条「电脑端取消了 …」
                    CancelStore.record(appContext, file.path, "${file.size}:${file.mtime}")
                    TransferHub.markCancelled(file.path, TransferCancelRegistry.Source.PEER)
                    log("电脑端取消了 ${file.path}，本次同步不再重试")
                },
            )
            if (bytes == null) {
                // 失败 / 被取消 / 电脑端拒绝：都要把传输状态清掉，否则界面会停在进度条上
                setTransfer(null)
                if (cancelRequested) {
                    cancelRequested = false
                    break
                }
                continue
            }
            setTransfer(null)
            if (writeDocument(file.path, bytes)) {
                remoteSnapshot[file.path] = file
                // 用「磁盘大小:磁盘时间:路径」登记，和 pushFiles 的去重键一致，
                // 否则刚收到的文件会被当成「本地变化」再发回电脑（回声）
                val localItem = collectLocal().firstOrNull { item -> item.path == file.path }
                if (localItem != null) {
                    val diskStamp = "${localItem.size}:${localItem.document.lastModified()}"
                    uploadedKeys += "$diskStamp:${localItem.path}"
                    baseline = (baseline ?: emptyMap()) + (localItem.path to diskStamp)
                }
                _state.value = _state.value.copy(downloaded = _state.value.downloaded + 1)
                log("收到 ${file.path}（${bytes.size / 1024} KB）")
            }
        }
    }

    /** 手机 → 远端 */
    private fun pushLocal(syncClient: SyncClient, manifest: RemoteManifest) {
        try {
            pushFiles(syncClient, manifest)
        } finally {
            setTransfer(null)
            cancelRequested = false
        }
    }

    private fun pushFiles(syncClient: SyncClient, manifest: RemoteManifest) {
        val items = collectLocal()
        if (items.isEmpty()) {
            // 目录读不到（SAF 权限丢失）或确实是空的：限频提示，别让用户以为"没反应"
            val now = System.currentTimeMillis()
            if (now - lastScanLogAt > 30_000) {
                lastScanLogAt = now
                log(
                    "同步文件夹里没有可同步的文件：${describeSyncTarget("")}" +
                        "（若目录里明明有文件，请到设置里重新选择文件夹）",
                )
            }
            return
        }
        val currentFingerprints = items.associate { it.path to "${it.size}:${it.document.lastModified()}" }
        // 文件内容变了：只解除对应那几条，其他（含下载侧刚记的）保持不动
        val before = CancelStore.load(appContext).map { it.path }.toSet()
        val keptRecords = CancelStore.reconcile(appContext, currentFingerprints)
        val keptPaths = keptRecords.map { it.path }.toSet()
        for (path in before - keptPaths) cancelledPaths.remove(path)
        for (path in keptPaths) cancelledPaths.add(path)
        var abortPass = false
        items.forEach { item ->
            if (abortPass) return@forEach
            val stamp = "${item.size}:${item.document.lastModified()}"
            if (baseline?.get(item.path) == stamp) return@forEach
            // 指纹带上修改时间：同名同大小但内容变过（mtime 变）也要重新传
            val key = "$stamp:${item.path}"
            if (key in uploadedKeys) return@forEach
            if (item.path in cancelledPaths) return@forEach
            val remote = manifest.files.firstOrNull { it.path == item.path }
            if (remote != null && remote.size == item.size) {
                val diff = item.document.lastModified() - remote.mtime
                // 时间接近 = 同一份；电脑那份更新 = 等它推过来；只有本端明显更新才发送
                if (kotlin.math.abs(diff) <= 2000 || diff < 0) {
                    uploadedKeys += key
                    return@forEach
                }
            }
            val bytes = readDocument(item) ?: return@forEach
            // 读文件的这几秒里用户可能已经点了取消：这里不能再无脑清标志
            if (cancelRequested || item.path in cancelledPaths) {
                cancelRequested = false
                cancelledPaths.add(item.path)
                log("已取消发送：${item.path}（本次同步不再重试）")
                return@forEach
            }
            var peerRejected = false
            val uploaded = syncClient.upload(item.path, bytes, deviceId, { sent, total ->
                // 已取消就别再刷进度了，否则界面会又冒出百分比
                if (!cancelRequested && item.path !in cancelledPaths) {
                    setTransfer(TransferInfo(item.path, sent, total, outgoing = true))
                }
            }, { cancelRequested || item.path in cancelledPaths }, { peerRejected = true })
            setTransfer(null)
            if (!uploaded && peerRejected) {
                cancelledPaths.add(item.path)
                // 持久化：重启 App 也不要再自动重传这个版本
                CancelStore.record(appContext, item.path, stamp)
                TransferHub.markCancelled(item.path, TransferCancelRegistry.Source.PEER)
                log("电脑端已取消 ${item.path}，本次同步不再重试")
                // 电脑端已经取消了：这一轮不要接着传后面的文件，否则界面又会冒出进度条
                abortPass = true
                return@forEach
            }
            if (!uploaded && cancelRequested) {
                cancelRequested = false
                cancelledPaths.add(item.path)
                CancelStore.record(appContext, item.path, stamp)
                TransferHub.markCancelled(item.path, TransferCancelRegistry.Source.LOCAL)
                log("已取消发送：${item.path}（本次同步不再重试）")
                abortPass = true
                return@forEach
            }
            if (uploaded) {
                uploadedKeys += key
                _state.value = _state.value.copy(uploaded = _state.value.uploaded + 1)
                log("已发送 ${item.path}（${bytes.size / 1024} KB）")
            }
        }
    }

    data class LocalItem(val path: String, val size: Long, val document: DocumentFile, val file: java.io.File?)

    /** 默认同步目录：应用外部文件目录下的 sync（不需要额外权限） */
    fun defaultFolder(): java.io.File {
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val dir = java.io.File(base, "sync")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 蓝牙同步复用：列出同步目录里的文件 */
    fun listSyncItems(): List<LocalItem> = collectLocal()

    /** 蓝牙同步复用：读一个文件的字节 */
    fun readSyncItem(item: LocalItem): ByteArray? = readDocument(item)

    /** 蓝牙同步复用：写一个文件（默认目录 / SAF 都支持） */
    fun writeSyncFile(path: String, bytes: ByteArray): Boolean = writeDocument(path, bytes)

    /** 蓝牙同步复用：设置修改时间（默认目录；SAF 目录尽力而为） */
    fun touchSyncFile(path: String, millis: Long) {
        runCatching {
            val root = tree()
            if (root == null) {
                java.io.File(defaultFolder(), path).setLastModified(millis)
            }
        }
    }

    /**
     * 用 SAF 选的目录；没选、或者授权已失效（重装 / 撤销权限）时返回 null，
     * 此时一律走应用默认目录 —— 保证「写文件的地方」和「打开同步文件夹」是同一个。
     */
    private fun tree(): DocumentFile? {
        val uri = _state.value.folderUri
        if (uri.isEmpty()) return null
        val doc = runCatching { DocumentFile.fromTreeUri(appContext, Uri.parse(uri)) }.getOrNull() ?: return null
        return if (runCatching { doc.canWrite() }.getOrDefault(false)) doc else null
    }

    /** 当前真正在用的目录：能用的 SAF uri，否则空字符串（表示应用默认目录） */
    fun activeFolderUri(): String = if (tree() != null) _state.value.folderUri else ""

    /** 界面展示用：当前同步目录（自定义目录 / 默认目录，以及是否可写） */
    fun syncFolderLabel(): String {
        val uri = _state.value.folderUri
        if (uri.isEmpty()) return "${defaultFolder().absolutePath}（默认）"
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        val tree = runCatching { DocumentFile.fromTreeUri(appContext, parsed ?: return "自定义目录") }.getOrNull()
        val writable = tree != null && runCatching { tree.canWrite() }.getOrDefault(false)
        val docId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(parsed)
        }.getOrNull() ?: uri
        val name = docId.substringAfter(':', docId)
        return if (writable) "$name（自定义）" else "$name（自定义，但不可写 → 用默认目录）"
    }

    /** 给日志用：某个文件最终落在哪 */
    fun describeSyncTarget(path: String): String {
        val root = tree()
        return if (root == null) {
            java.io.File(defaultFolder(), path).absolutePath
        } else {
            "${_state.value.folderUri}/$path"
        }
    }

    private fun collectLocal(): List<LocalItem> {
        val tree = tree()
        return if (tree != null) collectFromTree(tree) else collectFromFile(defaultFolder())
    }

    private fun collectFromTree(root: DocumentFile, prefix: String = ""): List<LocalItem> {
        val out = ArrayList<LocalItem>()
        val children = runCatching { root.listFiles() }.getOrNull() ?: return out
        for (child in children) {
            val name = child.name ?: continue
            if (skipFile(name)) continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                out += collectFromTree(child, path)
            } else if (child.isFile) {
                if (child.length() > maxFileBytes()) continue
                out += LocalItem(path, child.length(), child, null)
            }
        }
        return out
    }

    private fun collectFromFile(root: java.io.File, prefix: String = ""): List<LocalItem> {
        val out = ArrayList<LocalItem>()
        val children = runCatching { root.listFiles() }.getOrNull() ?: return out
        for (child in children) {
            if (skipFile(child.name)) continue
            val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                out += collectFromFile(child, path)
            } else if (child.isFile) {
                if (child.length() > maxFileBytes()) continue
                out += LocalItem(path, child.length(), DocumentFile.fromFile(child), child)
            }
        }
        return out
    }

    private fun skipFile(name: String): Boolean =
        name.startsWith(".") || name.startsWith("~$") || name.endsWith(".tmp") || name.endsWith(".part")

    private fun readDocument(item: LocalItem): ByteArray? = runCatching {
        val file = item.file
        if (file != null) file.readBytes()
        else appContext.contentResolver.openInputStream(item.document.uri)?.use { it.readBytes() }
    }.getOrNull()

    /** 写入一个文件；默认目录用普通文件 API，SAF 目录走 DocumentFile */
    private fun writeDocument(path: String, bytes: ByteArray): Boolean {
        val root = tree()
        if (root == null) {
            return runCatching {
                val target = java.io.File(defaultFolder(), path)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                true
            }.getOrDefault(false)
        }
        val written = runCatching {
            val segments = path.split('/')
            var current: DocumentFile = root
            for (index in 0 until segments.size - 1) {
                val name = segments[index]
                current = current.findFile(name)?.takeIf { it.isDirectory }
                    ?: current.createDirectory(name)
                    ?: return false
            }
            val fileName = segments.last()
            val target = current.findFile(fileName)?.takeIf { it.isFile }
                ?: current.createFile(guessMime(fileName), fileName)
                ?: return false
            appContext.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
            true
        }.getOrDefault(false)
        if (written) return true
        // 自定义目录写不进去（授权失效等）时退回应用默认目录，别把文件弄丢
        log("自定义目录不可写，改写到应用默认目录：$path")
        return runCatching {
            val target = java.io.File(defaultFolder(), path)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    private fun guessMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "md", "txt", "log" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun log(text: String) {
        val entry = SyncLogEntry(System.currentTimeMillis(), text)
        _state.value = _state.value.copy(logs = (listOf(entry) + _state.value.logs).take(30))
    }

    companion object {
        private const val PREFS = "file-sync"
        private const val KEY_ADDRESS = "address"
        private const val KEY_FOLDER = "folder"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_MAX_MB = "maxFileMb"
        private const val INTERVAL_MS = 3_000L
    }
}
