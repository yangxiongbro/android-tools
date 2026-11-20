package com.screenrecorder.app.ui

import android.util.Size
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.screenrecorder.app.core.AudioSourcePreset
import com.screenrecorder.app.core.RecorderSettings
import com.screenrecorder.app.core.RecorderState
import com.screenrecorder.app.core.SaveLocation
import com.screenrecorder.app.storage.RecordingItem
import com.screenrecorder.app.storage.RecordingsRepository
import com.screenrecorder.app.util.DisplayUtils
import com.screenrecorder.app.util.Formatters
import kotlinx.coroutines.delay

/**
 * 首页（工具箱）。屏幕录制是主卡片，其余工具由 [ToolRegistry] 驱动，
 * 后续新增工具无需改动这个页面的布局。
 */
@Composable
fun ToolHubScreen(
    settings: RecorderSettings,
    state: RecorderState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit,
    onToolClick: (ToolSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val displaySize = remember { DisplayUtils.currentDisplaySize(context) }
    val captureSize = remember(settings, displaySize) {
        settings.captureSize(displaySize.width, displaySize.height)
    }
    val bitrateKbps = remember(settings, captureSize) {
        settings.videoBitrateKbps(captureSize.width, captureSize.height)
    }

    val repository = remember { RecordingsRepository(context) }
    var recent by remember { mutableStateOf<List<RecordingItem>>(emptyList()) }
    var recentLoading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey, settings.saveLocation, settings.customFolderUri) {
        recent = repository.load(settings).take(3)
        recentLoading = false
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reloadKey++
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        AppHeader(
            title = "工具",
            subtitle = "录屏与更多实用工具",
        )

        Spacer(Modifier.height(8.dp))

        RecorderHero(
            settings = settings,
            state = state,
            captureSize = captureSize,
            bitrateKbps = bitrateKbps,
            onStart = onStart,
            onStop = onStop,
            onPause = onPause,
            onResume = onResume,
            onOpenSettings = onOpenSettings,
        )

        SectionHeader(title = "更多工具")
        ToolGrid(tools = ToolRegistry.otherTools, onToolClick = onToolClick)

        SectionHeader(
            title = "最近录制",
            actionLabel = if (recent.isEmpty()) null else "全部",
            onAction = if (recent.isEmpty()) null else onOpenFiles,
        )

        when {
            recentLoading -> Unit

            recent.isEmpty() -> Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                EmptyState(
                    icon = Icons.Outlined.VideoLibrary,
                    title = "还没有录制文件",
                    description = "点击上方按钮开始第一次录制，完成后会出现在这里",
                )
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                recent.forEach { item ->
                    RecordingCard(
                        item = item,
                        compact = true,
                        onPlay = {
                            runCatching { context.startActivity(repository.playIntent(item)) }
                                .onFailure {
                                    android.widget.Toast
                                        .makeText(context, "没有可用的播放器", android.widget.Toast.LENGTH_SHORT)
                                        .show()
                                }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** 工具网格：双列排布，新增工具会自动补位。 */
@Composable
private fun ToolGrid(tools: List<ToolSpec>, onToolClick: (ToolSpec) -> Unit) {
    Column {
        tools.chunked(2).forEach { rowTools ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowTools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        onClick = { onToolClick(tool) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowTools.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolSpec, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
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
            IconBadge(icon = tool.icon, accent = tool.accent, size = 38.dp, iconSize = 20.dp)
            Spacer(Modifier.height(12.dp))
            Text(text = tool.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                text = tool.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            PillBadge(
                text = tool.status.label,
                container = tool.accent.first().copy(alpha = 0.13f),
                content = tool.accent.first(),
            )
        }
    }
}

/** 屏幕录制主卡片：渐变底色 + 状态徽章 + 录制控制。 */
@Composable
private fun RecorderHero(
    settings: RecorderSettings,
    state: RecorderState,
    captureSize: Size,
    bitrateKbps: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(AppAccents.Record)),
    ) {
        // 装饰光斑：让纯色渐变更有层次，同时被卡片圆角裁切。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 64.dp, y = (-72).dp)
                .size(196.dp)
                .background(Color.White.copy(alpha = 0.10f), CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-54).dp, y = 62.dp)
                .size(132.dp)
                .background(Color.White.copy(alpha = 0.07f), CircleShape),
        )

        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ToolRegistry.primary.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        text = ToolRegistry.primary.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                    )
                }
                PillBadge(
                    text = when (state) {
                        is RecorderState.Preparing -> "准备中"
                        is RecorderState.Recording -> if (state.paused) "已暂停" else "录制中"
                        is RecorderState.Failed -> "失败"
                        else -> "可用"
                    },
                    container = Color.White.copy(alpha = 0.18f),
                    content = Color.White,
                )
            }

            Spacer(Modifier.height(18.dp))

            when (state) {
                is RecorderState.Recording -> RecordingBody(
                    state = state,
                    onPause = onPause,
                    onResume = onResume,
                    onStop = onStop,
                )

                is RecorderState.Preparing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "正在准备录制…",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                }

                else -> IdleBody(
                    settings = settings,
                    captureSize = captureSize,
                    bitrateKbps = bitrateKbps,
                    onStart = onStart,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun IdleBody(
    settings: RecorderSettings,
    captureSize: Size,
    bitrateKbps: Int,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RecordButton(onClick = onStart)
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "开始录制",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${settings.captureMode.label} · ${settings.resolution.label}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.82f),
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenSettings)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = buildString {
                append("${captureSize.width}×${captureSize.height}")
                append(" · ${settings.frameRate.label}")
                append(" · ${Formatters.bitrate(bitrateKbps)}")
                append(" · ${settings.audioSource.shortLabel()}")
                append(" · ${settings.saveLocation.shortLabel()}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "调整配置",
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RecordingBody(
    state: RecorderState.Recording,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    var elapsed by remember(state.startedAtMillis) {
        mutableLongStateOf(System.currentTimeMillis() - state.startedAtMillis)
    }
    LaunchedEffect(state.startedAtMillis) {
        while (true) {
            elapsed = System.currentTimeMillis() - state.startedAtMillis
            delay(500L)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        PulsingDot(active = !state.paused)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (state.paused) "已暂停" else "正在录制",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }

    Spacer(Modifier.height(4.dp))

    Text(
        text = Formatters.duration(elapsed),
        fontSize = 40.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = Color.White,
    )

    Spacer(Modifier.height(2.dp))

    Text(
        text = state.fileName.ifBlank { "正在准备文件…" },
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.85f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = state.resolution,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.7f),
    )

    Spacer(Modifier.height(16.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HeroAction(
            icon = if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            label = if (state.paused) "继续" else "暂停",
            filled = false,
            onClick = if (state.paused) onResume else onPause,
            modifier = Modifier.weight(1f),
        )
        HeroAction(
            icon = Icons.Filled.Stop,
            label = "停止",
            filled = true,
            onClick = onStop,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HeroAction(
    icon: ImageVector,
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (filled) Color.White else Color.White.copy(alpha = 0.18f)
    val content = if (filled) AppAccents.Record[1] else Color.White
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
        }
    }
}

/** 白色快门按钮：外圈呼吸描边 + 内部红色圆点。 */
@Composable
private fun RecordButton(onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "recordPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .border(2.dp, Color.White.copy(alpha = pulse), CircleShape),
        )
        Surface(
            shape = CircleShape,
            color = Color.White,
            modifier = Modifier
                .size(70.dp)
                .clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(AppAccents.Record[1]),
                )
            }
        }
    }
}

@Composable
private fun PulsingDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .background(
                color = Color.White.copy(alpha = if (active) alpha else 0.45f),
                shape = CircleShape,
            ),
    )
}

private fun AudioSourcePreset.shortLabel(): String = when (this) {
    AudioSourcePreset.NONE -> "无声"
    AudioSourcePreset.MIC -> "麦克风"
    AudioSourcePreset.INTERNAL -> "内部声音"
}

private fun SaveLocation.shortLabel(): String = when (this) {
    SaveLocation.MEDIA_STORE -> "相册"
    SaveLocation.CUSTOM_FOLDER -> "自定义目录"
    SaveLocation.APP_PRIVATE -> "应用目录"
}
