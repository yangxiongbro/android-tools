package com.android_tools.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** 工具箱里一个工具的状态。 */
enum class ToolStatus(val label: String) {
    READY("可用"),
    PLANNED("即将推出"),
}

/** 工具箱中的工具标识。 */
enum class ToolId {
    SCREEN_RECORDING,
    FILE_SYNC,
}

/**
 * 一个工具在工具页上的描述信息。界面只依赖这份数据，因此新增工具时不用改工具页布局。
 *
 * @param accent 工具强调色（渐变色标），用于图标徽章、状态胶囊等。
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
 * 工具注册表 —— 工具页直接渲染 [tools]，新增工具只需在这里加一条：
 *
 * 1. 在 [ToolId] 里加一项；
 * 2. 在 [tools] 里加一条 [ToolSpec]（[ToolStatus.PLANNED] 表示占位，点击只提示「即将推出」）；
 * 3. 在 [com.android_tools.app.ui.RecorderApp] 的 [Route] 分发处挂上它自己的页面。
 */
object ToolRegistry {

    /** 全部工具，按展示顺序排列（工具页为双列网格，顺序即阅读顺序）。 */
    val tools: List<ToolSpec> = listOf(
        ToolSpec(
            id = ToolId.SCREEN_RECORDING,
            title = "录屏工具",
            summary = "整屏或单个应用录制",
            icon = Icons.Outlined.Videocam,
            accent = AppAccents.Record,
        ),
        ToolSpec(
            id = ToolId.FILE_SYNC,
            title = "文件同步",
            summary = "WiFi 与电脑同步 / 蓝牙互传",
            icon = Icons.Outlined.FolderShared,
            accent = AppAccents.Sync,
        ),
    )

    fun find(id: ToolId): ToolSpec = tools.first { it.id == id }
}
