package com.android_tools.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.android_tools.app.core.RecorderSettings
import com.android_tools.app.storage.RecordingItem
import com.android_tools.app.storage.RecordingsRepository
import com.android_tools.app.util.Formatters
import kotlinx.coroutines.launch

@Composable
fun RecordingsScreen(
    settings: RecorderSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { RecordingsRepository(context) }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<RecordingItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var deleteTarget by remember { mutableStateOf<RecordingItem?>(null) }

    LaunchedEffect(reloadKey, settings.saveLocation, settings.customFolderUri) {
        loading = true
        items = repository.load(settings)
        loading = false
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reloadKey++
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        SubPageHeader(
            title = "录制文件",
            subtitle = when {
                loading -> "正在读取…"
                items.isEmpty() -> "这里会出现在本机保存的录制视频"
                else -> "${items.size} 个文件 · 共 ${Formatters.fileSize(items.sumOf { it.sizeBytes })}"
            },
            onBack = onBack,
        )

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                items.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.VideoLibrary,
                    title = "还没有录制文件",
                    description = "回到录屏工具页点击录制按钮，即可开始第一次录制",
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.uri.toString() }) { item ->
                        RecordingCard(
                            item = item,
                            onPlay = {
                                runCatching { context.startActivity(repository.playIntent(item)) }
                                    .onFailure {
                                        Toast.makeText(context, "没有可用的播放器", Toast.LENGTH_SHORT)
                                            .show()
                                    }
                            },
                            onShare = {
                                runCatching { context.startActivity(repository.shareIntent(item)) }
                                    .onFailure {
                                        Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
                                    }
                            },
                            onDelete = { deleteTarget = item },
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除录制文件") },
            text = { Text("确定删除「${target.name}」吗？该操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            val deleted = repository.delete(target)
                            if (!deleted) {
                                Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                            }
                            reloadKey++
                        }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}
