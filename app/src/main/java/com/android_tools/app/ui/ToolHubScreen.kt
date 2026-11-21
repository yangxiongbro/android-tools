package com.android_tools.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android_tools.app.core.RecorderState

/**
 * 工具页：所有工具按双列卡片网格排布，内容完全由 [ToolRegistry.tools] 驱动。
 * 每个工具的具体操作都在点击进入的工具页里。
 */
@Composable
fun ToolHubScreen(
    recorderState: RecorderState,
    onOpenTool: (ToolSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        AppHeader(
            title = "工具",
            subtitle = "共 ${ToolRegistry.tools.size} 个工具，点击卡片开始使用",
        )

        Spacer(Modifier.height(14.dp))

        ToolGrid(
            tools = ToolRegistry.tools,
            recorderState = recorderState,
            onOpenTool = onOpenTool,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "所有工具都在本机运行，录制文件与同步数据不会上传到服务器。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )

        Spacer(Modifier.height(24.dp))
    }
}

/** 工具网格：双列排布，新增工具会自动补位。 */
@Composable
private fun ToolGrid(
    tools: List<ToolSpec>,
    recorderState: RecorderState,
    onOpenTool: (ToolSpec) -> Unit,
) {
    Column {
        tools.chunked(2).forEach { rowTools ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowTools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        statusText = statusTextOf(tool, recorderState),
                        statusColor = statusColorOf(tool, recorderState),
                        onClick = { onOpenTool(tool) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowTools.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolCard(
    tool: ToolSpec,
    statusText: String,
    statusColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(152.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(15.dp),
        ) {
            IconBadge(icon = tool.icon, accent = tool.accent, size = 40.dp, iconSize = 21.dp)
            Spacer(Modifier.height(12.dp))
            Text(text = tool.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(
                text = tool.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            PillBadge(
                text = statusText,
                container = statusColor.copy(alpha = 0.13f),
                content = statusColor,
            )
        }
    }
}

private fun statusTextOf(tool: ToolSpec, state: RecorderState): String = when {
    tool.id != ToolId.SCREEN_RECORDING -> tool.status.label
    state is RecorderState.Recording -> if (state.paused) "已暂停" else "录制中"
    state is RecorderState.Preparing -> "准备中"
    else -> tool.status.label
}

private fun statusColorOf(tool: ToolSpec, state: RecorderState): Color = when {
    tool.id == ToolId.SCREEN_RECORDING &&
        (state is RecorderState.Recording || state is RecorderState.Preparing) -> AppAccents.Record[1]
    else -> tool.accent.first()
}
