package com.android_tools.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android_tools.app.chat.ChatRuntime
import com.android_tools.app.chat.ChatStore

/**
 * 本地聊天 → 设置页：昵称、头像、监听端口。
 *
 * 和工具页共用同一个引擎（[ChatRuntime]），改完立刻生效，返回后监听不会中断。
 */
@Composable
fun LocalChatSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { ChatRuntime.engine(context) }
    val state by engine.state.collectAsStateWithLifecycle()

    var nameInput by remember(state.me.name) { mutableStateOf(state.me.name) }
    var avatar by remember(state.me.avatar) { mutableStateOf(state.me.avatar) }
    var portInput by remember(state.me.port) { mutableStateOf(state.me.port.toString()) }
    var hint by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        SubPageHeader(
            title = "聊天设置",
            subtitle = "昵称、头像与监听端口",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("我的资料", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "好友看到的名字与头像",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChatStore.AVATARS.forEach { item ->
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { avatar = item }
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    item,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = if (item == avatar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it.take(20) },
                        label = { Text("昵称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        val name = nameInput.trim()
                        if (name.isEmpty()) {
                            hint = "昵称不能为空"
                        } else {
                            engine.updateProfile(name, avatar)
                            hint = "资料已保存"
                        }
                    }) { Text("保存资料") }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("监听端口", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "对方按「本机地址:端口」添加你；改端口会重启监听",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { value -> portInput = value.filter { it.isDigit() }.take(5) },
                            singleLine = true,
                            modifier = Modifier.width(140.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Button(onClick = {
                            val port = portInput.toIntOrNull()
                            if (port == null || port !in 1024..65535) {
                                hint = "端口范围 1024 - 65535"
                            } else {
                                engine.updatePort(port)
                                portInput = engine.state.value.me.port.toString()
                                hint = "端口已改为 $port"
                            }
                        }) { Text("保存端口") }
                    }
                    if (state.addresses.isNotEmpty()) {
                        Text(
                            "本机地址：" + state.addresses.joinToString(" / ") { "$it:${state.me.port}" },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("安全", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "消息用 X25519 协商密钥 + AES-256-GCM 端到端加密，和电脑端协议一致；" +
                            "HTTPS 证书首次连接时记录指纹，之后变化会提示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (state.supported) "当前系统支持加密聊天（Android 13+）" else "当前系统版本过低，无法加密聊天（需要 Android 13+）",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.supported) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
