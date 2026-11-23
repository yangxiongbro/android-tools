package com.android_tools.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField

import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android_tools.app.sync.SyncEngine
import com.android_tools.app.sync.SyncRuntime

/**
 * 设置 →「文件同步」里的同步配置页。
 *
 * 之前这个入口直接跳到了文件同步工具，用户点进来以为能改设置，结果进了同步页面；
 * 现在这里是独立的设置页：同步目录、单个文件上限都放这儿，工具页只负责同步本身。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSyncSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 必须是进程里同一个引擎：否则这里改了目录，文件同步页（另一个实例）读到的还是旧状态
    val engine = remember { SyncRuntime.engine(context) }
    var maxMbInput by remember { mutableStateOf(engine.maxFileMb().toString()) }
    var folderLabel by remember { mutableStateOf(engine.syncFolderLabel()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { folderLabel = engine.syncFolderLabel() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            engine.updateFolder(uri)
            folderLabel = engine.syncFolderLabel()
            android.widget.Toast.makeText(context, folderLabel, android.widget.Toast.LENGTH_LONG).show()
        } else {
            android.widget.Toast.makeText(context, "没有拿到文件夹授权", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SubPageHeader(
            title = "文件同步设置",
            subtitle = "同步目录与单个文件上限",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("同步目录", style = MaterialTheme.typography.titleSmall)
                    Text(
                        folderLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("更换同步文件夹")
                        }
                        OutlinedButton(
                            onClick = { openSyncFolderForSettings(context, engine) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("打开同步文件夹")
                        }
                    }
                    Text(
                        "WiFi 与蓝牙同步共用这个目录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("单个文件上限", style = MaterialTheme.typography.titleSmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = maxMbInput,
                            onValueChange = { value -> maxMbInput = value.filter { it.isDigit() }.take(4) },
                            singleLine = true,
                            modifier = Modifier.width(120.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            suffix = { Text("MB") },
                        )
                        Button(onClick = {
                            val mb = maxMbInput.toIntOrNull() ?: 100
                            engine.updateMaxFileMb(mb)
                            maxMbInput = engine.maxFileMb().toString()
                        }) { Text("保存") }
                    }
                    Text(
                        "超过上限的文件不会同步（默认 100 MB，可设 1 - 4096）。所有类型的文件都会同步。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 设置页里也要能打开同步目录：复用工具页那套解析逻辑 */
private fun openSyncFolderForSettings(context: android.content.Context, engine: SyncEngine) {
    val folderUri = engine.activeFolderUri()
    val mime = android.provider.DocumentsContract.Document.MIME_TYPE_DIR
    val candidates = ArrayList<Uri>()
    if (folderUri.isNotEmpty()) {
        val treeUri = Uri.parse(folderUri)
        val docUri = runCatching {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        }.getOrNull()
        if (docUri != null) candidates += docUri
        candidates += treeUri
    } else {
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
        candidates += Uri.parse("content://com.android.externalstorage.documents/document/${Uri.encode(docId)}")
    }
    for (uri in candidates) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}
