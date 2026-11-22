package com.android_tools.app.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地聊天（参考 PC 端的「本地聊天」）：局域网内点对点文字聊天。
 *
 * 好友通过「IP:端口」添加，消息正文用 ECDH + AES-GCM 端到端加密。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalChatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { ChatEngine(context) }
    val state by engine.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var addInput by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }
    var openFriendId by remember { mutableStateOf<String?>(null) }
    val uiScope = rememberCoroutineScope()
    val friend = openFriendId?.let { id -> state.friends.firstOrNull { it.id == id } }

    LaunchedEffect(openFriendId) { engine.openFriend(openFriendId) }

    DisposableEffect(Unit) {
        engine.startServer()
        onDispose { engine.shutdown() }
    }

    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    if (friend != null) {
        ChatDetail(
            friend = friend,
            messages = state.messages.filter { it.friendId == friend.id },
            formatter = formatter,
            onBack = { openFriendId = null },
            onSend = { text -> engine.sendText(friend.id, text) },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地聊天") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加好友")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加好友")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 我的资料
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ChatStore.avatarOf(state.me.avatar), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.me.name.ifEmpty { "我" }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "监听端口 ${state.me.port} · " + if (state.listening) "已开启" else "未开启",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = {
                            if (state.listening) engine.stopServer() else engine.startServer()
                        }) { Text(if (state.listening) "停止" else "开启") }
                    }
                    if (state.addresses.isNotEmpty()) {
                        Text(
                            "本机地址：" + state.addresses.joinToString(" / ") { "$it:${state.me.port}" },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.error.isNotEmpty()) {
                        Text(state.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("好友", "申请" + if (state.requests.isNotEmpty()) " (${state.requests.size})" else "").forEachIndexed { index, label ->
                    if (tab == index) {
                        Button(onClick = { tab = index }, modifier = Modifier.weight(1f)) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { tab = index }, modifier = Modifier.weight(1f)) { Text(label) }
                    }
                }
            }

            if (tab == 0) {
                if (state.friends.isEmpty()) {
                    Text(
                        "还没有好友，点右上角「+」用对方的 IP:端口 添加",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                state.friends.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .clickable { openFriendId = item.id },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ChatStore.avatarOf(item.avatar), style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.name.ifEmpty { item.ip }, style = MaterialTheme.typography.bodyLarge)
                                    if (System.currentTimeMillis() - item.lastSeenAt < 90_000) {
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            Modifier.width(6.dp).height(6.dp)
                                                .background(Color(0xFF22C55E), RoundedCornerShape(3.dp)),
                                        )
                                    }
                                }
                                Text(
                                    ChatStore.preview(item.lastMessage).ifEmpty { "${item.ip}:${item.port}" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            if (item.unread > 0) {
                                Text(
                                    " ${item.unread} ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)),
                                )
                            }
                        }
                    }
                }
            } else {
                if (state.requests.isEmpty()) {
                    Text("没有待处理的好友申请", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
                state.requests.forEach { request ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                (if (request.outgoing) "我添加：" else "添加我：") + request.name.ifEmpty { request.ip },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${request.ip}:${request.port} · ${formatter.format(Date(request.at))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!request.outgoing) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { engine.respondRequest(request.id, true) }) { Text("同意") }
                                    OutlinedButton(onClick = { engine.respondRequest(request.id, false) }) { Text("拒绝") }
                                }
                            } else {
                                TextButton(onClick = { engine.dismissRequest(request.id) }) { Text("撤回申请") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false; addError = "" },
            title = { Text("添加好友") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = addInput,
                        onValueChange = { addInput = it },
                        label = { Text("对方地址") },
                        placeholder = { Text("192.168.1.8:45900") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Text(
                        "让对方打开「本地聊天」并开启，把上面的地址填进来即可。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (addError.isNotEmpty()) {
                        Text(addError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val address = addInput
                    addError = "正在连接…"
                    uiScope.launch {
                        val error = withContext(Dispatchers.IO) { engine.addFriend(address) }
                        if (error == null) {
                            showAdd = false
                            addInput = ""
                            addError = ""
                        } else {
                            addError = error
                        }
                    }
                }) { Text("发送申请") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; addError = "" }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetail(
    friend: ChatFriend,
    messages: List<ChatMessage>,
    formatter: SimpleDateFormat,
    onBack: () -> Unit,
    onSend: (String) -> String?,
) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(friend.name.ifEmpty { friend.ip }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages) { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
                    ) {
                        Column(horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
                            Text(
                                message.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .background(
                                        if (message.outgoing) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            Text(
                                formatter.format(Date(message.at)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (error.isNotEmpty()) {
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("说点什么…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                IconButton(onClick = {
                    val failure = onSend(draft)
                    if (failure == null) {
                        draft = ""
                        error = ""
                    } else {
                        error = failure
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}
