package com.android_tools.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android_tools.app.apps.ForegroundAppMonitor
import com.android_tools.app.capture.FloatingControl
import com.android_tools.app.core.CaptureMode
import com.android_tools.app.core.RecorderController
import com.android_tools.app.core.RecorderSettings
import com.android_tools.app.core.RecorderState
import com.android_tools.app.core.RecordingCommands
import com.android_tools.app.core.SaveLocation
import com.android_tools.app.core.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppTab(val label: String) {
    TOOLS("工具"),
    SETTINGS("设置"),
    ;

    fun icon(selected: Boolean): ImageVector = when (this) {
        TOOLS -> if (selected) Icons.Filled.Dashboard else Icons.Outlined.Dashboard
        SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
    }
}

/** 盖在底部导航之上的页面（工具页与工具内的二级页）。 */
enum class Route {
    RECORDER_TOOL,
    RECORDINGS,
    RECORDER_SETTINGS,
    FILE_SYNC,
}

@Composable
fun RecorderApp() {
    val context = LocalContext.current
    val repository = remember { SettingsRepository.get(context) }
    val settings by repository.settings.collectAsStateWithLifecycle()
    val recorderState by RecorderController.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val foregroundMonitor = remember(context) { ForegroundAppMonitor(context) }

    var tab by rememberSaveable { mutableStateOf(AppTab.TOOLS) }
    var showAppPicker by remember { mutableStateOf(false) }
    // 页面栈用「A/B」这样的字符串保存：从系统文件选择器回来时进程可能被回收，
    // 只有恢复到原来那一页，「保存位置 / 同步文件夹」的回调才能收到结果。
    var routePath by rememberSaveable { mutableStateOf("") }
    val routes = remember(routePath) {
        routePath.split('/')
            .filter { it.isNotBlank() }
            .mapNotNull { name -> Route.entries.firstOrNull { it.name == name } }
    }
    val currentRoute = routes.lastOrNull()
    val push: (Route) -> Unit = { route ->
        routePath = if (routePath.isBlank()) route.name else "$routePath/${route.name}"
    }
    val pop: () -> Unit = { routePath = routePath.substringBeforeLast('/', "") }

    var countdown by remember { mutableIntStateOf(0) }
    var startSignal by remember { mutableIntStateOf(0) }
    var hasUsageAccess by remember { mutableStateOf(foregroundMonitor.hasPermission()) }
    var canDrawOverlays by remember { mutableStateOf(FloatingControl.canDrawOverlays(context)) }

    BackHandler(enabled = routes.isNotEmpty()) { pop() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasUsageAccess = foregroundMonitor.hasPermission()
        canDrawOverlays = FloatingControl.canDrawOverlays(context)
    }

    val requestOverlayPermission: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            Toast.makeText(context, "无法打开悬浮窗权限设置", Toast.LENGTH_SHORT).show()
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            RecordingCommands.start(context, result.resultCode, data)
        } else {
            RecorderController.publish(RecorderState.Failed("已取消屏幕捕获授权"))
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchProjection(context, settings, projectionLauncher)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("未授予麦克风权限，本次将不录制声音")
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The foreground service notification is shown regardless of this result. */ }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            repository.update {
                it.copy(saveLocation = SaveLocation.CUSTOM_FOLDER, customFolderUri = uri.toString())
            }
        }
    }

    val beginRecording: () -> Unit = {
        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (settings.hasAudio && !audioGranted) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjection(context, settings, projectionLauncher)
        }
    }

    // Asked once on first launch so it never competes with the screen capture dialog.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(startSignal) {
        if (startSignal == 0) return@LaunchedEffect
        if (settings.floatingControl && !canDrawOverlays) {
            // Do not block the recording, just tell the user why the floating button is missing.
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "未授予悬浮窗权限，本次录制不显示悬浮按钮",
                    actionLabel = "去授权",
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    requestOverlayPermission()
                }
            }
        }
        for (value in settings.countdownSeconds downTo 1) {
            countdown = value
            delay(1_000L)
        }
        countdown = 0
        beginRecording()
    }

    LaunchedEffect(recorderState) {
        when (val state = recorderState) {
            is RecorderState.Finished -> {
                scope.launch { snackbarHostState.showSnackbar("已保存：${state.fileName}") }
                RecorderController.reset()
            }

            is RecorderState.Failed -> {
                scope.launch { snackbarHostState.showSnackbar(state.message) }
                RecorderController.reset()
            }

            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            BottomNav(
                selected = tab,
                recording = recorderState is RecorderState.Recording,
                onSelect = {
                    // 点底部导航等于回到顶层页面
                    tab = it
                    routePath = ""
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (currentRoute) {
                Route.RECORDER_TOOL -> RecorderToolScreen(
                    settings = settings,
                    state = recorderState,
                    onBack = pop,
                    onOpenSettings = { push(Route.RECORDER_SETTINGS) },
                    onOpenFiles = { push(Route.RECORDINGS) },
                    onStart = {
                        if (recorderState !is RecorderState.Recording) startSignal++
                    },
                    onStop = { RecordingCommands.stop(context) },
                    onPause = { RecordingCommands.pause(context) },
                    onResume = { RecordingCommands.resume(context) },
                )

                Route.RECORDINGS -> RecordingsScreen(
                    settings = settings,
                    onBack = pop,
                )

                Route.RECORDER_SETTINGS -> RecorderSettingsScreen(
                    settings = settings,
                    hasUsageAccess = hasUsageAccess,
                    canDrawOverlays = canDrawOverlays,
                    onUpdate = repository::update,
                    onPickFolder = { folderLauncher.launch(null) },
                    onPickApp = { showAppPicker = true },
                    onOpenUsageAccess = {
                        runCatching {
                            context.startActivity(ForegroundAppMonitor.usageAccessSettingsIntent())
                        }.onFailure {
                            Toast.makeText(context, "无法打开使用情况访问设置", Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                    onRequestOverlayPermission = requestOverlayPermission,
                    onBack = pop,
                )

                Route.FILE_SYNC -> FileSyncScreen(onBack = pop)

                null -> when (tab) {
                    AppTab.TOOLS -> ToolHubScreen(
                        recorderState = recorderState,
                        onOpenTool = { tool ->
                            when (tool.id) {
                                ToolId.SCREEN_RECORDING -> push(Route.RECORDER_TOOL)
                                ToolId.FILE_SYNC -> push(Route.FILE_SYNC)
                            }
                        },
                    )

                    AppTab.SETTINGS -> SettingsScreen(
                        onOpenRecorderSettings = { push(Route.RECORDER_SETTINGS) },
                        onOpenFileSync = { push(Route.FILE_SYNC) },
                    )
                }
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            currentPackage = settings.targetPackage,
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                repository.update { it.copy(targetPackage = app.packageName, targetLabel = app.label) }
            },
        )
    }

    if (countdown > 0) {
        Dialog(
            onDismissRequest = { },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(AppAccents.Record)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$countdown",
                        fontSize = 68.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = "即将开始录制",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

/** 底部浮动导航栏：选中项展开为「图标 + 文字」的胶囊。 */
@Composable
private fun BottomNav(
    selected: AppTab,
    recording: Boolean,
    onSelect: (AppTab) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppTab.entries.forEach { item ->
                    NavItem(
                        item = item,
                        selected = selected == item,
                        showRecordingDot = recording && item == AppTab.TOOLS,
                        onClick = { onSelect(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    item: AppTab,
    selected: Boolean,
    showRecordingDot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "navTint",
    )
    val indicator by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        },
        label = "navIndicator",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(indicator)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(
                imageVector = item.icon(selected),
                contentDescription = item.label,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
            if (showRecordingDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(7.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(7.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
        }
    }
}

private fun launchProjection(
    context: Context,
    settings: RecorderSettings,
    launcher: ActivityResultLauncher<Intent>,
) {
    val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return
    val intent = when {
        // Android 14+: ask explicitly for the whole display, otherwise the system picker may
        // default to a single app capture.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            settings.captureMode == CaptureMode.SINGLE_APP ->
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())

        else -> manager.createScreenCaptureIntent()
    }
    launcher.launch(intent)
}
