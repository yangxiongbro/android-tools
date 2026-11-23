package com.android_tools.app.ui

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android_tools.app.sync.BluetoothSync
import com.android_tools.app.sync.BtSyncEngine
import com.android_tools.app.notify.Notify
import com.android_tools.app.sync.SyncEngine
import com.android_tools.app.sync.SyncRuntime
import com.android_tools.app.sync.TransferInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件同步工具：手机与电脑（DevToolbox「文件同步」）通过 WiFi 双向同步，
 * 也支持通过蓝牙与电脑 / 另一台手机互传。
 *
 * 页面顺序：连接方式 → 已配对设备 / 电脑地址 → 同步按钮 → 传输状态 → 同步记录；
 * 「选择同步文件夹」放在「设置 → 文件同步」里，这里只保留「打开同步文件夹」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSyncScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    // 引擎放在进程里（SyncRuntime），离开页面 / 切后台传输不会断
    val engine = remember { SyncRuntime.engine(context) }
    val bluetooth = remember { SyncRuntime.bluetooth(context) }
    val state by engine.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    // 不预填示例地址：留空 + placeholder 提示，避免用户没改就直接点「开始同步」
    var addressInput by remember { mutableStateOf(state.address) }

    // ---- 蓝牙同步的运行状态（和 WiFi 共用状态区 / 记录区） ----
    val btUi by SyncRuntime.btState.collectAsStateWithLifecycle()
    val btRunning = btUi.running
    val btDevice = btUi.device
    val btSent = btUi.sent
    val btReceived = btUi.received
    val btTransfer = btUi.transfer
    val btLogs = SyncRuntime.btLogs.collectAsStateWithLifecycle().value
    var pickedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var permissionGranted by remember { mutableStateOf(bluetooth.hasPermission()) }
    val devices = remember(permissionGranted) { mutableStateListOf<BluetoothDevice>() }

    val btSync = remember { SyncRuntime.bt(context) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted = result.values.all { it }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            devices.clear()
            devices.addAll(bluetooth.bondedDevices())
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 注意：这里故意不停止 engine / btSync —— 返回工具页或切后台后传输继续，
    // 用户点「停止同步」才会停。

    LaunchedEffect(Unit) {
        Notify.ensureChannels(context)
        if (!Notify.canPost(context)) {
            runCatching { notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }


    val transfer = if (tab == 0) state.transfer else btTransfer

    val running = if (tab == 0) state.running else btRunning
    val statusText = when {
        !running -> "未开启"
        tab == 0 && !state.connected -> "连接中…（${state.address.ifEmpty { "未填地址" }}）"
        tab == 1 -> "已连接 ${btDevice.ifEmpty { "等待手机连接" }}"
        else -> "已连接 ${state.address}"
    }

    val records = remember(state.logs, btLogs) {
        (state.logs.map { it.at to it.text } + btLogs)
            .sortedByDescending { it.first }
            .take(200)
    }
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        SubPageHeader(
            title = "文件同步",
            subtitle = "WiFi 与电脑同步 / 蓝牙互传",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp),
            trailing = {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "同步设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------- 连接方式 ----------
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("连接方式", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("WiFi / 局域网", "蓝牙").forEachIndexed { index, label ->
                        if (tab == index) {
                            Button(onClick = { tab = index }, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                        } else {
                            OutlinedButton(onClick = { tab = index }, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                        }
                    }
                }
            }

            // ---------- 同步目录（只读；换目录在设置里） ----------
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "同步目录：" + engine.syncFolderLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { openSyncFolder(context, engine.activeFolderUri(), engine) }) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("打开", style = MaterialTheme.typography.labelMedium)
                }
            }

            // ---------- 1. 已配对设备 / 电脑地址 ----------
            if (tab == 0) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        label = { Text("电脑地址") },
                        placeholder = { Text("192.168.1.8:45872") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "电脑端「文件同步」开启后会显示地址，手机与电脑连同一个 WiFi 即可。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!bluetooth.isAvailable()) {
                        Text("蓝牙未开启或设备不支持", color = MaterialTheme.colorScheme.error)
                    }
                    if (!permissionGranted) {
                        Button(onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.BLUETOOTH_CONNECT,
                                    android.Manifest.permission.BLUETOOTH_SCAN,
                                ),
                            )
                        }) { Text("授予蓝牙权限") }
                    }
                    Text("已配对设备", style = MaterialTheme.typography.titleSmall)
                    if (devices.isEmpty()) {
                        Text("没有已配对的设备", style = MaterialTheme.typography.bodySmall)
                    }
                    // 固定高度滚动窗口
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                        items(devices) { device ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clickable { pickedDevice = device },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Bluetooth, contentDescription = null)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(deviceNameOf(device), style = MaterialTheme.typography.bodyMedium)
                                        Text(device.address, style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (pickedDevice?.address == device.address) {
                                        Text(
                                            "已选择",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 2. 同步按钮 ----------
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Button(
                    onClick = {
                        if (tab == 0) {
                            engine.updateAddress(addressInput)
                            if (state.running) engine.stop() else engine.start()
                        } else if (btRunning) {
                            btSync.stop()
                        } else {
                            val device = pickedDevice
                            if (device == null) {
                                engine.note("先选择要同步的设备")
                            } else {
                                btSync.start(device, bluetooth.adapter)
                            }
                        }
                    },
                    enabled = if (tab == 1) permissionGranted && (btRunning || pickedDevice != null) else true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            tab == 0 && state.running -> "停止同步"
                            tab == 1 && btRunning -> "停止蓝牙同步"
                            tab == 1 -> "开启蓝牙同步"
                            else -> "开始同步"
                        },
                    )
                }
            }

            // ---------- 3. 传输状态 ----------
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("传输状态", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "● " + statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tab == 0 && state.lastError.isNotEmpty()) {
                        Text(state.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (transfer != null && transfer.total > 0) {
                        Text(
                            (if (transfer.outgoing) "上传 " else "下载 ") + transfer.path,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LinearProgressIndicator(
                            progress = { transfer.sent.toFloat() / transfer.total.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Text(
                            "${transfer.percent}% · ${transfer.sent / 1024} KB / ${transfer.total / 1024} KB",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        Text("当前没有正在传输的文件", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (tab == 0) {
                            "本次已下载 ${state.downloaded} 个 · 已上传 ${state.uploaded} 个"
                        } else {
                            "已发送 $btSent 个 · 已接收 $btReceived 个"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // ---------- 4. 同步记录（固定高度滚动窗口） ----------
            Text("同步记录", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (records.isEmpty()) {
                    Text(
                        "还没有记录",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(180.dp).padding(8.dp)) {
                        items(records) { (at, text) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    formatter.format(Date(at)),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(64.dp),
                                )
                                Text(text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 用系统文件管理器打开同步目录。
 *
 * - 用 SAF 选过目录：转成 document uri 交给文件管理器（直接进这个文件夹）；
 * - 默认目录：用 ExternalStorageProvider 的 document uri 打开；
 * - 都打不开时退化成让用户重新选一次目录。
 */
private fun openSyncFolder(context: Context, folderUri: String, engine: SyncEngine) {
    val mime = DocumentsContract.Document.MIME_TYPE_DIR
    val candidates = ArrayList<Uri>()

    if (folderUri.isNotEmpty()) {
        // tree uri 交给文件管理器只会停在上级目录；转成 document uri 才能直接打开这个文件夹
        val treeUri = Uri.parse(folderUri)
        val docUri = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        }.getOrNull()
        if (docUri != null) candidates += docUri
        candidates += treeUri
    } else {
        // 用真实的默认目录去拼 document id：有的机器外部存储不在 primary
        // （比如应用数据落在 SD 卡），写死 primary 会打开另一个空目录。
        val dir = engine.defaultFolder().absolutePath.replace('\\', '/')
        val storageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath.replace('\\', '/')
        val docId = when {
            dir.startsWith("$storageRoot/") -> "primary:" + dir.removePrefix("$storageRoot/")
            dir.startsWith("/storage/") -> {
                val rest = dir.removePrefix("/storage/")
                val volume = rest.substringBefore('/')
                val tail = rest.substringAfter('/', "")
                "$volume:$tail"
            }
            else -> "primary:Android/data/${context.packageName}/files/sync"
        }
        candidates += Uri.parse(
            "content://com.android.externalstorage.documents/document/${Uri.encode(docId)}"
        )
    }

    for (uri in candidates) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (launched) return
    }

    // 兜底：让用户用系统的目录选择器挑一次（挑完就变成 SAF 目录）
    android.util.Log.w("FileSync", "没有可用的文件管理器，改用目录选择器")
    val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(picker) }
        .onFailure { android.util.Log.w("FileSync", "打开同步目录失败: ${it.message}", it) }
}

@Composable
private fun deviceNameOf(device: BluetoothDevice): String {
    val name = runCatching { device.name }.getOrNull()
    return name ?: device.address
}
