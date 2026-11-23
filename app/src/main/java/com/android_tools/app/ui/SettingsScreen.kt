package com.android_tools.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android_tools.app.BuildConfig

/**
 * 设置页：只保留应用级内容与「工具设置」目录，录屏工具的具体参数在
 * [RecorderSettingsScreen] 二级页里（点「录屏工具」进入）。
 */
@Composable
fun SettingsScreen(
    onOpenRecorderSettings: () -> Unit,
    onOpenFileSync: () -> Unit,
    onOpenLocalChatSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val version = BuildConfig.VERSION_NAME.substringBefore("-")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        AppHeader(
            title = "设置",
            subtitle = "应用信息与各工具的设置入口",
            modifier = Modifier.padding(horizontal = 18.dp),
        )

        Spacer(Modifier.padding(top = 8.dp))

        SettingsGroup(title = "工具设置", icon = Icons.Outlined.Apps) {
            SettingsRow(
                title = "屏幕录制",
                subtitle = "录制范围、画质、声音、保存位置",
                leading = Icons.Outlined.Videocam,
                leadingTint = AppAccents.Record[1],
                onClick = onOpenRecorderSettings,
            )
            RowDivider()
            SettingsRow(
                title = "文件同步",
                subtitle = "同步目录、单个文件大小上限",
                leading = Icons.Outlined.FolderShared,
                leadingTint = AppAccents.Sync[1],
                onClick = onOpenFileSync,
            )
            RowDivider()
            SettingsRow(
                title = "本地聊天",
                subtitle = "昵称、头像与监听端口",
                leading = Icons.Outlined.Chat,
                leadingTint = AppAccents.Chat[1],
                onClick = onOpenLocalChatSettings,
            )
        }

        SettingsGroup(title = "关于", icon = Icons.Outlined.Info) {
            SettingsRow(
                title = "版本",
                value = version,
                leading = Icons.Outlined.Info,
            )
            RowDivider()
            SettingsRow(
                title = "数据存储",
                subtitle = "录制文件保存在本机，同步数据只在你指定的设备之间传输",
                leading = Icons.Outlined.Storage,
            )
        }

    }
}
