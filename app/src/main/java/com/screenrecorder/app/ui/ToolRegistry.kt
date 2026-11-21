package com.screenrecorder.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** 工具栏里一个工具的状态。 */
enum class ToolStatus(val label: String) {
    READY("可用"),
    PLANNED("即将推出"),
}

/** 工具箱中的工具标识。 */
enum class ToolId {
    SCREEN_RECORDING,
    SCREENSHOT,
    AUDIO_RECORDING,
    FILE_SYNC,
}

/**
 * 一个工具在首页上的描述信息。界面只依赖这份数据，因此新增工具时不用改首页布局。
 *
 * @param accent 工具强调色（渐变色标），用于图标徽章、占位卡片等。
 */
data class ToolSpec(
    val id: ToolId,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val accent: List<Color>,
    val status: ToolStatus = ToolStatus.READY,
)

/**
 * 工具注册表 —— 首页的「更多工具」区域直接渲染 [tools]，新增工具只需在这里加一条：
 *
 * 1. 在 [ToolId] 里加一项；
 * 2. 在 [tools] 里加一条 [ToolSpec]（[ToolStatus.PLANNED] 表示占位，点击只提示「即将推出」）；
 * 3. 可用的工具在 [com.screenrecorder.app.ui.RecorderApp] 的工具分发处挂上它自己的界面。
 *
 * 屏幕录制本身就是首页的主卡片，因此 [otherTools] 里不再重复列出它。
 */
object ToolRegistry {

    /** 首页主卡片展示的主工具。 */
    val primary: ToolSpec = ToolSpec(
        id = ToolId.SCREEN_RECORDING,
        title = "屏幕录制",
        summary = "整屏或单个应用",
        icon = Icons.Outlined.Videocam,
        accent = AppAccents.Record,
    )

    /** 全部工具（含主工具），按展示顺序排列。 */
    val tools: List<ToolSpec> = listOf(
        primary,
        ToolSpec(
            id = ToolId.SCREENSHOT,
            title = "截图",
            summary = "长截图与快捷标注",
            icon = Icons.Outlined.PhotoCamera,
            accent = AppAccents.Screenshot,
            status = ToolStatus.PLANNED,
        ),
        ToolSpec(
            id = ToolId.AUDIO_RECORDING,
            title = "录音",
            summary = "麦克风与内部声音",
            icon = Icons.Outlined.Mic,
            accent = AppAccents.Audio,
            status = ToolStatus.PLANNED,
        ),
        ToolSpec(
            id = ToolId.FILE_SYNC,
            title = "文件同步",
            summary = "WiFi 与电脑同步 / 蓝牙互传",
            icon = Icons.Outlined.FolderShared,
            accent = AppAccents.Screenshot,
        ),
    )

    /** 主工具以外的工具，用于首页网格。 */
    val otherTools: List<ToolSpec> get() = tools.filter { it.id != primary.id }

    fun find(id: ToolId): ToolSpec = tools.first { it.id == id }
}
