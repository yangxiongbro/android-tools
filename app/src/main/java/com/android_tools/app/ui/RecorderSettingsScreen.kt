package com.android_tools.app.ui

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.android_tools.app.core.AudioSourcePreset
import com.android_tools.app.core.CaptureMode
import com.android_tools.app.core.FrameRatePreset
import com.android_tools.app.core.QualityPreset
import com.android_tools.app.core.RecorderSettings
import com.android_tools.app.core.ResolutionPreset
import com.android_tools.app.core.SaveLocation
import com.android_tools.app.core.VideoCodecPreset
import com.android_tools.app.util.Formatters

private enum class SettingsDialog {
    CAPTURE_MODE,
    RESOLUTION,
    FRAME_RATE,
    QUALITY,
    BITRATE,
    CODEC,
    AUDIO_SOURCE,
    AUDIO_BITRATE,
    SAVE_LOCATION,
    COUNTDOWN,
    MAX_DURATION,
    PREFIX,
}

/**
 * 录屏工具的设置二级页。从「设置 → 工具设置 → 录屏工具」进入，
 * 也可以从录屏工具页右上角的齿轮直接进入。
 */
@Composable
fun RecorderSettingsScreen(
    settings: RecorderSettings,
    hasUsageAccess: Boolean,
    canDrawOverlays: Boolean,
    onUpdate: ((RecorderSettings) -> RecorderSettings) -> Unit,
    onPickFolder: () -> Unit,
    onPickApp: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openDialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val singleAppSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        SubPageHeader(
            title = "录屏设置",
            subtitle = "录制范围、画质、声音与保存位置",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        Spacer(Modifier.padding(top = 6.dp))

        SettingsGroup(title = "录制范围", icon = Icons.Outlined.CropFree) {
            SettingsRow(
                title = "录制范围",
                subtitle = if (singleAppSupported) {
                    "单个应用需要 Android 14 及以上"
                } else {
                    "当前系统仅支持录制整个屏幕"
                },
                value = settings.captureMode.label,
                leading = Icons.Outlined.CropFree,
                onClick = { openDialog = SettingsDialog.CAPTURE_MODE },
            )
            if (settings.captureMode == CaptureMode.SINGLE_APP) {
                RowDivider()
                SettingsRow(
                    title = "目标应用",
                    subtitle = "仅用于提示与自动停止",
                    value = settings.targetLabel ?: "未选择",
                    leading = Icons.Outlined.Apps,
                    onClick = onPickApp,
                )
                RowDivider()
                SettingsSwitchRow(
                    title = "离开目标应用后停止",
                    subtitle = if (hasUsageAccess) {
                        "需要「使用情况访问」权限，已在其他应用停留 4 秒后自动结束"
                    } else {
                        "需要「使用情况访问」权限，点击前往授权"
                    },
                    checked = settings.autoStopWhenTargetLeaves && hasUsageAccess,
                    enabled = settings.targetPackage != null,
                    leading = Icons.AutoMirrored.Outlined.ExitToApp,
                    onCheckedChange = { checked ->
                        if (hasUsageAccess) {
                            onUpdate { it.copy(autoStopWhenTargetLeaves = checked) }
                        } else {
                            onOpenUsageAccess()
                        }
                    },
                )
            }
        }

        SettingsGroup(title = "画质与性能", icon = Icons.Outlined.Tune) {
            SettingsRow(
                title = "分辨率",
                value = settings.resolution.label,
                leading = Icons.Outlined.HighQuality,
                onClick = { openDialog = SettingsDialog.RESOLUTION },
            )
            RowDivider()
            SettingsRow(
                title = "帧率",
                subtitle = "固定帧率；画面静止时会重复上一帧",
                value = settings.frameRate.label,
                leading = Icons.Outlined.Speed,
                onClick = { openDialog = SettingsDialog.FRAME_RATE },
            )
            RowDivider()
            SettingsRow(
                title = "画质预设",
                value = settings.quality.label,
                leading = Icons.Outlined.Tune,
                onClick = { openDialog = SettingsDialog.QUALITY },
            )
            RowDivider()
            SettingsRow(
                title = "视频码率",
                value = if (settings.customBitrateKbps > 0) {
                    Formatters.bitrate(settings.customBitrateKbps)
                } else {
                    "自动"
                },
                leading = Icons.Outlined.DataUsage,
                onClick = { openDialog = SettingsDialog.BITRATE },
            )
            RowDivider()
            SettingsRow(
                title = "编码格式",
                value = settings.codec.label,
                leading = Icons.Outlined.Memory,
                onClick = { openDialog = SettingsDialog.CODEC },
            )
        }

        SettingsGroup(title = "声音", icon = Icons.AutoMirrored.Outlined.VolumeUp) {
            SettingsRow(
                title = "声音来源",
                subtitle = if (settings.audioSource == AudioSourcePreset.INTERNAL) {
                    "仅录制允许被捕获的应用声音"
                } else {
                    null
                },
                value = settings.audioSource.label,
                leading = Icons.AutoMirrored.Outlined.VolumeUp,
                onClick = { openDialog = SettingsDialog.AUDIO_SOURCE },
            )
            if (settings.hasAudio) {
                RowDivider()
                SettingsRow(
                    title = "音频码率",
                    value = "${settings.audioBitrateKbps} Kbps",
                    leading = Icons.Outlined.GraphicEq,
                    onClick = { openDialog = SettingsDialog.AUDIO_BITRATE },
                )
            }
        }

        SettingsGroup(title = "保存", icon = Icons.Outlined.Folder) {
            SettingsRow(
                title = "保存位置",
                value = settings.saveLocation.label,
                leading = Icons.Outlined.Folder,
                onClick = { openDialog = SettingsDialog.SAVE_LOCATION },
            )
            if (settings.saveLocation == SaveLocation.CUSTOM_FOLDER) {
                RowDivider()
                SettingsRow(
                    title = "自定义文件夹",
                    subtitle = "通过系统文件选择器授权，重启后仍然有效",
                    value = settings.customFolderUri?.let(::prettyFolderName) ?: "未选择",
                    leading = Icons.Outlined.Folder,
                    onClick = onPickFolder,
                )
            }
            RowDivider()
            SettingsRow(
                title = "文件名前缀",
                subtitle = "最终命名为 前缀_日期_时间.mp4",
                value = settings.fileNamePrefix,
                leading = Icons.Outlined.DriveFileRenameOutline,
                onClick = { openDialog = SettingsDialog.PREFIX },
            )
        }

        SettingsGroup(title = "录制行为", icon = Icons.Outlined.Timer) {
            SettingsRow(
                title = "开始倒计时",
                value = if (settings.countdownSeconds == 0) "关闭" else "${settings.countdownSeconds} 秒",
                leading = Icons.Outlined.Timer,
                onClick = { openDialog = SettingsDialog.COUNTDOWN },
            )
            RowDivider()
            SettingsRow(
                title = "最长录制时长",
                value = if (settings.maxDurationMinutes == 0) {
                    "不限制"
                } else {
                    "${settings.maxDurationMinutes} 分钟"
                },
                leading = Icons.Outlined.Schedule,
                onClick = { openDialog = SettingsDialog.MAX_DURATION },
            )
            RowDivider()
            SettingsSwitchRow(
                title = "保持 CPU 唤醒",
                subtitle = "长时间录制时避免系统休眠导致丢帧",
                checked = settings.keepCpuAwake,
                leading = Icons.Outlined.BatteryChargingFull,
                onCheckedChange = { checked -> onUpdate { it.copy(keepCpuAwake = checked) } },
            )
            RowDivider()
            SettingsSwitchRow(
                title = "悬浮控制按钮",
                subtitle = if (canDrawOverlays) {
                    "录制时悬浮显示：点击暂停/继续，长按停止，可拖动"
                } else {
                    "需要「显示在其他应用上层」权限，点击前往授权"
                },
                checked = settings.floatingControl,
                leading = Icons.Outlined.PictureInPictureAlt,
                onCheckedChange = { checked ->
                    if (checked && !canDrawOverlays) {
                        onRequestOverlayPermission()
                    } else {
                        onUpdate { it.copy(floatingControl = checked) }
                    }
                },
            )
        }

        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "提示：录制中旋转屏幕时画面会等比缩放（出现黑边），视频分辨率在整个录制过程中保持不变。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "录制文件仅保存在本机，不会上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    when (openDialog) {
        SettingsDialog.CAPTURE_MODE -> ChoiceDialog(
            title = "录制范围",
            options = CaptureMode.entries,
            selected = settings.captureMode,
            optionLabel = { it.label },
            onDismiss = { openDialog = null },
            onSelect = { mode ->
                if (mode == CaptureMode.SINGLE_APP && !singleAppSupported) {
                    Toast.makeText(
                        context,
                        "单个应用录制需要 Android 14 及以上",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    onUpdate { it.copy(captureMode = mode) }
                }
            },
        )

        SettingsDialog.RESOLUTION -> ChoiceDialog(
            title = "分辨率",
            options = ResolutionPreset.entries,
            selected = settings.resolution,
            optionLabel = { it.label },
            onDismiss = { openDialog = null },
            onSelect = { preset -> onUpdate { it.copy(resolution = preset) } },
        )

        SettingsDialog.FRAME_RATE -> ChoiceDialog(
            title = "帧率",
            options = FrameRatePreset.entries,
            selected = settings.frameRate,
            optionLabel = { "${it.label}（${it.fps} 帧/秒）" },
            onDismiss = { openDialog = null },
            onSelect = { preset -> onUpdate { it.copy(frameRate = preset) } },
        )

        SettingsDialog.QUALITY -> ChoiceDialog(
            title = "画质预设",
            options = QualityPreset.entries,
            selected = settings.quality,
            optionLabel = { it.label },
            onDismiss = { openDialog = null },
            onSelect = { preset -> onUpdate { it.copy(quality = preset) } },
        )

        SettingsDialog.CODEC -> ChoiceDialog(
            title = "编码格式",
            options = VideoCodecPreset.entries,
            selected = settings.codec,
            optionLabel = { it.label },
            onDismiss = { openDialog = null },
            onSelect = { preset -> onUpdate { it.copy(codec = preset) } },
        )

        SettingsDialog.AUDIO_SOURCE -> ChoiceDialog(
            title = "声音来源",
            options = AudioSourcePreset.entries,
            selected = settings.audioSource,
            optionLabel = { it.label },
            onDismiss = { openDialog = null },
            onSelect = { preset -> onUpdate { it.copy(audioSource = preset) } },
        )

        SettingsDialog.SAVE_LOCATION -> ChoiceDialog(
            title = "保存位置",
            options = SaveLocation.entries,
            selected = settings.saveLocation,
            optionLabel = { it.label },
            onDismiss = { openDialog = null },
            onSelect = { preset ->
                onUpdate { it.copy(saveLocation = preset) }
                if (preset == SaveLocation.CUSTOM_FOLDER && settings.customFolderUri == null) {
                    onPickFolder()
                }
            },
        )

        SettingsDialog.BITRATE -> SliderDialog(
            title = "视频码率",
            initialValue = if (settings.customBitrateKbps > 0) settings.customBitrateKbps else 8_000,
            min = 1_000,
            max = 50_000,
            step = 500,
            format = { Formatters.bitrate(it) },
            neutralLabel = "自动（根据分辨率与帧率计算）",
            neutralValue = 0,
            onDismiss = { openDialog = null },
            onConfirm = { value -> onUpdate { it.copy(customBitrateKbps = value) } },
        )

        SettingsDialog.AUDIO_BITRATE -> SliderDialog(
            title = "音频码率",
            initialValue = settings.audioBitrateKbps,
            min = 64,
            max = 320,
            step = 32,
            format = { "$it Kbps" },
            onDismiss = { openDialog = null },
            onConfirm = { value -> onUpdate { it.copy(audioBitrateKbps = value) } },
        )

        SettingsDialog.COUNTDOWN -> SliderDialog(
            title = "开始倒计时",
            initialValue = settings.countdownSeconds,
            min = 0,
            max = 10,
            step = 1,
            format = { if (it == 0) "关闭" else "$it 秒" },
            onDismiss = { openDialog = null },
            onConfirm = { value -> onUpdate { it.copy(countdownSeconds = value) } },
        )

        SettingsDialog.MAX_DURATION -> SliderDialog(
            title = "最长录制时长",
            initialValue = settings.maxDurationMinutes,
            min = 0,
            max = 60,
            step = 5,
            format = { if (it == 0) "不限制" else "$it 分钟" },
            neutralLabel = "不限制",
            neutralValue = 0,
            onDismiss = { openDialog = null },
            onConfirm = { value -> onUpdate { it.copy(maxDurationMinutes = value) } },
        )

        SettingsDialog.PREFIX -> TextInputDialog(
            title = "文件名前缀",
            initialValue = settings.fileNamePrefix,
            onDismiss = { openDialog = null },
            onConfirm = { value -> onUpdate { it.copy(fileNamePrefix = value) } },
        )

        null -> Unit
    }
}

private fun prettyFolderName(uriString: String): String {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return "未选择"
    val segments = uri.pathSegments
    val last = segments.lastOrNull()?.substringAfterLast(':') ?: return "已选择"
    return if (last.isBlank()) "已选择" else last
}
