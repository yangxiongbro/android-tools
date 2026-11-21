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

        AppBanner(version = version)

        SettingsGroup(title = "工具设置", icon = Icons.Outlined.Apps) {
            SettingsRow(
                title = "录屏工具",
                subtitle = "录制范围、画质、声音、保存位置",
                leading = Icons.Outlined.Videocam,
                leadingTint = AppAccents.Record[1],
                onClick = onOpenRecorderSettings,
            )
            RowDivider()
            SettingsRow(
                title = "文件同步",
                subtitle = "同步文件夹、连接地址在工具页内设置",
                leading = Icons.Outlined.FolderShared,
                leadingTint = AppAccents.Sync[1],
                onClick = onOpenFileSync,
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

        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "工具库 · $version",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

/** 顶部应用信息卡：应用名、定位说明与当前版本号。 */
@Composable
private fun AppBanner(version: String) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = Icons.Filled.Dashboard,
                accent = AppAccents.Brand,
                size = 46.dp,
                iconSize = 24.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "工具库", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "本地工具集，数据不会上传",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PillBadge(
                text = "v$version",
                container = AppAccents.Brand.first().copy(alpha = 0.14f),
                content = AppAccents.Brand.last(),
            )
        }
    }
}
