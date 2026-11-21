package com.android_tools.app.ui

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import android.net.Uri
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android_tools.app.sync.BluetoothSync
import com.android_tools.app.sync.BtSyncEngine
import com.android_tools.app.sync.SyncEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件同步工具：手机与电脑（DevToolbox「文件同步」）通过 WiFi 双向同步，
 * 也支持两台手机通过蓝牙互传文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { SyncEngine(context) }
    val bluetooth = remember { BluetoothSync(context) }
    val state by engine.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    // 不预填示例地址：留空 + placeholder 提示，避免用户没改就直接点「开始同步」
    var addressInput by remember { mutableStateOf(state.address) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            engine.updateFolder(uri)
            android.widget.Toast.makeText(context, engine.syncFolderLabel(), android.widget.Toast.LENGTH_LONG).show()
        } else {
            // 有的机型（三星 My Files 等）在确认框被取消 / 回调丢失时会拿到 null
            engine.note("没有拿到文件夹授权：请再点一次「更换同步文件夹」，选好目录后点「使用此文件夹」并在弹窗里选「允许」")
            android.widget.Toast.makeText(context, "没有拿到文件夹授权", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engine.stop()
            bluetooth.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文件同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 连接方式：WiFi / 蓝牙 合并成一个页面，用分段按钮切换
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("连接方式", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("WiFi / 局域网", "蓝牙").forEachIndexed { index, label ->
                        val selected = tab == index
                        if (selected) {
                            Button(onClick = { tab = index }, modifier = Modifier.weight(1f)) {
                                Text(label, maxLines = 1)
                            }
                        } else {
                            OutlinedButton(onClick = { tab = index }, modifier = Modifier.weight(1f)) {
                                Text(label, maxLines = 1)
                            }
                        }
                    }
                }
            }
            // 单个文件上限（两种连接方式共用）
            var maxMbInput by remember { mutableStateOf(state.maxFileMb.toString()) }
            LaunchedEffect(state.maxFileMb) { maxMbInput = state.maxFileMb.toString() }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("单个文件上限", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = maxMbInput,
                    onValueChange = { value -> maxMbInput = value.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    modifier = Modifier.width(96.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    suffix = { Text("MB") },
                )
                Button(
                    onClick = {
                        val mb = maxMbInput.toIntOrNull() ?: 100
                        engine.updateMaxFileMb(mb)
                    },
                ) { Text("保存") }
            }
            Text(
                "超过上限的文件不会同步（默认 100 MB）；所有类型的文件都会同步。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            if (tab == 0) {
                WifiTab(
                    state = state,
                    folderLabel = engine.syncFolderLabel(),
                    grantedFolders = engine.grantedFolders(),
                    onApplyFolder = { uri -> engine.updateFolder(Uri.parse(uri)) },
                    addressInput = addressInput,
                    onAddressChange = { addressInput = it },
                    onPickFolder = { folderPicker.launch(null) },
                    onOpenFolder = { openSyncFolder(context, engine.activeFolderUri(), engine) },
                    onToggle = {
                        engine.updateAddress(addressInput)
                        if (state.running) engine.stop() else engine.start()
                    },
                )
            } else {
                BluetoothTab(
                    context = context,
                    bluetooth = bluetooth,
                    engine = engine,
                    onPickFolder = { folderPicker.launch(null) },
                )
            }
        }
    }
}

/**
 * 用系统文件管理器打开同步目录。
 *
 * - 用 SAF 选过目录：直接把这个 tree uri 交给文件管理器；
 * - 默认目录（Android/data/<包名>/files/sync）：用 ExternalStorageProvider 的
 *   document uri 打开（FileProvider 不能给「根目录本身」生成 uri）；
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
private fun WifiTab(
    state: com.android_tools.app.sync.SyncUiState,
    folderLabel: String,
    grantedFolders: List<String>,
    onApplyFolder: (String) -> Unit,
    addressInput: String,
    onAddressChange: (String) -> Unit,
    onPickFolder: () -> Unit,
    onOpenFolder: () -> Unit,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "在电脑上打开 DevToolbox →「文件同步」→ 开启同步，把界面上显示的地址填到下面。手机与电脑要连同一个 WiFi。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = addressInput,
            onValueChange = onAddressChange,
            label = { Text("电脑地址") },
            placeholder = { Text("192.168.1.8:45872") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickFolder, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("更换同步文件夹")
            }
            OutlinedButton(onClick = onOpenFolder, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("打开同步文件夹")
            }
        }
        if (grantedFolders.isNotEmpty()) {
            Text(
                "已授权但没用上的目录（点一下切换）：",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            grantedFolders.take(3).forEach { uri ->
                Text(
                    text = runCatching { Uri.decode(uri) }.getOrDefault(uri),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onApplyFolder(uri) }.padding(vertical = 4.dp),
                )
            }
        }
        Text(
            text = "同步目录：$folderLabel",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Icon(if (state.running) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.running) "停止同步" else "开始同步")
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("状态", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        state.running && state.connected -> "已连接"
                        state.running -> "连接中…"
                        else -> "未开始"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.lastError.isNotEmpty()) {
                    Text(state.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "远端清单：${state.remoteCount} 个文件（版本 ${state.remoteVersion.ifEmpty { "-" }}）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "本次已下载 ${state.downloaded} 个 · 已上传 ${state.uploaded} 个",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text("同步记录", style = MaterialTheme.typography.titleSmall)
        if (state.logs.isEmpty()) {
            Text("还没有记录", style = MaterialTheme.typography.bodySmall)
        }
        val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
        state.logs.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatter.format(Date(entry.at)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(64.dp),
                )
                Text(entry.text, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BluetoothTab(
    context: Context,
    bluetooth: BluetoothSync,
    engine: SyncEngine,
    onPickFolder: () -> Unit,
) {
    val syncState by engine.state.collectAsStateWithLifecycle()
    var permissionGranted by remember { mutableStateOf(bluetooth.hasPermission()) }
    var running by remember { mutableStateOf(false) }
    var deviceLabel by remember { mutableStateOf("") }
    var sentCount by remember { mutableIntStateOf(0) }
    var receivedCount by remember { mutableIntStateOf(0) }
    var log by remember { mutableStateOf("") }
    val devices = remember(permissionGranted) { mutableStateListOf<BluetoothDevice>() }
    var pickedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    val uiHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    val btSync = remember {
        BtSyncEngine(
            engine = engine,
            onLog = { message -> uiHandler.post { log = message } },
            onState = { alive, device, sent, received ->
                uiHandler.post {
                    running = alive
                    deviceLabel = device
                    sentCount = sent
                    receivedCount = received
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose { btSync.stop() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted = result.values.all { it }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            devices.clear()
            devices.addAll(bluetooth.bondedDevices())
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "蓝牙同步和 WiFi 同步一样：选一台已配对的设备开启后，两边同步文件夹里的新增 / 修改会自动互传（不需要 WiFi）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickFolder, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("更换同步文件夹")
            }
            OutlinedButton(onClick = { openSyncFolder(context, engine.activeFolderUri(), engine) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("打开同步文件夹")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (running) {
                        btSync.stop()
                        running = false
                    } else {
                        val device = pickedDevice
                        if (device == null) {
                            log = "先选择要同步的设备"
                        } else {
                            btSync.start(device, bluetooth.adapter)
                            running = true
                        }
                    }
                },
                enabled = permissionGranted && (running || pickedDevice != null),
            ) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "停止蓝牙同步" else "开启蓝牙同步")
            }
        }

        Text(
            if (running) "已开启 · ${deviceLabel.ifEmpty { "等待连接" }} · 已发送 $sentCount · 已接收 $receivedCount"
            else "未开启",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            // 读一下 syncState 让选了文件夹之后这里能重组刷新
            "同步目录：" + engine.syncFolderLabel() + if (syncState.folderUri.isEmpty()) "" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (log.isNotEmpty()) {
            Text(log, style = MaterialTheme.typography.bodySmall)
        }

        Text("已配对设备", style = MaterialTheme.typography.titleSmall)
        if (devices.isEmpty()) {
            Text("没有已配对的设备", style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            items(devices) { device ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickableDevice { pickedDevice = device },
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
                            Text("已选择", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun Modifier.clickableDevice(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@Composable
private fun deviceNameOf(device: BluetoothDevice): String {
    val name = runCatching { device.name }.getOrNull()
    return name ?: device.address
}
