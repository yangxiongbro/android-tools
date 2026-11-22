package com.android_tools.app.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.android_tools.app.notify.Notify
import org.json.JSONObject
import java.io.File
import java.security.KeyPair
import java.util.UUID
import javax.net.ssl.SSLServerSocket

/** 聊天界面状态 */
data class ChatUiState(
    val me: ChatMe = ChatMe("", "", "", 45900),
    val friends: List<ChatFriend> = emptyList(),
    val requests: List<ChatRequest> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val listening: Boolean = false,
    val supported: Boolean = true,
    val error: String = "",
    val addresses: List<String> = emptyList(),
)

/**
 * 本地聊天引擎：协议与 PC 端「本地聊天」完全一致（HTTPS + X25519 + AES-GCM），
 * 因此可以和电脑、也可以和另一台安卓手机互通。
 */
class ChatEngine(context: Context) {
    private companion object {
        const val KEY_VERSION = 2
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("local-chat", Context.MODE_PRIVATE)
    private val store = ChatStore(File(appContext.filesDir, "chat.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var server: SSLServerSocket? = null
    private var pollJob: Job? = null
    private var keyPair: KeyPair? = null
    private var currentFriendId: String? = null

    init {
        store.load()
        keyPair = if (ChatCrypto.isSupported) loadKeyPair() else null
        publish()
    }

    private fun loadKeyPair(): KeyPair? {
        // 1.3.7 之前用的是 P-256 密钥，和 PC 端的 X25519 不兼容，这里用版本号强制换新
        val version = prefs.getInt("keyVersion", 0)
        val publicKey = if (version == KEY_VERSION) ChatCrypto.decodePublic(prefs.getString("pub", "").orEmpty()) else null
        val privateKey = if (version == KEY_VERSION) ChatCrypto.decodePrivate(prefs.getString("priv", "").orEmpty()) else null
        if (publicKey != null && privateKey != null) return KeyPair(publicKey, privateKey)
        val generated = runCatching { ChatCrypto.generateKeyPair() }
            .onFailure { android.util.Log.e("ChatEngine", "生成 X25519 密钥失败", it) }
            .getOrNull() ?: return null
        prefs.edit()
            .putString("pub", ChatCrypto.encodePublic(generated.public))
            .putString("priv", ChatCrypto.encodePrivate(generated.private))
            .putInt("keyVersion", KEY_VERSION)
            .apply()
        return generated
    }

    private fun myPub(): String = keyPair?.let { ChatCrypto.encodePublic(it.public) } ?: ""

    fun updateProfile(name: String, avatar: String) {
        store.updateMe(name.trim().ifEmpty { store.me.name }, avatar, store.me.port)
        store.save()
        publish()
    }

    fun updatePort(port: Int) {
        store.updateMe(store.me.name, store.me.avatar, port.coerceIn(1024, 65535))
        store.save()
        publish()
        if (server != null) {
            stopServer()
            startServer()
        }
    }

    fun openFriend(friendId: String?) {
        currentFriendId = friendId
        if (friendId != null) {
            val index = store.friends.indexOfFirst { it.id == friendId }
            if (index >= 0) {
                store.friends[index] = store.friends[index].copy(unread = 0)
                store.save()
            }
        }
        publish()
    }

    fun startServer() {
        if (server != null) return
        if (!ChatCrypto.isSupported) {
            _state.value = _state.value.copy(error = "需要 Android 13 及以上才能加密聊天")
            return
        }
        val port = store.me.port
        server = ChatNet.serve(appContext, port) { body, remoteIp -> handlePayload(body, remoteIp) }
        if (server == null) {
            _state.value = _state.value.copy(listening = false, error = "端口 $port 被占用，换个端口再试")
            return
        }
        _state.value = _state.value.copy(listening = true, error = "")
        startPolling()
        publish()
    }

    fun stopServer() {
        runCatching { server?.close() }
        server = null
        pollJob?.cancel()
        pollJob = null
        _state.value = _state.value.copy(listening = false)
    }

    fun shutdown() {
        stopServer()
        scope.coroutineContext[Job]?.cancel()
    }

    // ------------------------------------------------------------------
    // 报文
    // ------------------------------------------------------------------

    /** 我在别人眼里的信息（对应 PC 的 peerBrief） */
    private fun myBrief(): JSONObject = JSONObject()
        .put("id", store.me.id)
        .put("nickname", store.me.name)
        .put("avatar", store.me.avatar)
        .put("port", store.me.port)
        .put("pub", myPub())
        .put("host", ChatNet.localAddresses().firstOrNull() ?: "")

    /** 用对方公钥加密 payload（对应 PC 的 sealPayload） */
    private fun sealFor(peerPub: String, payload: JSONObject): JSONObject? {
        val shared = keyPair?.let { ChatCrypto.sharedKey(it.private, myPub(), peerPub) } ?: return null
        return runCatching { ChatCrypto.seal(shared, payload) }.getOrNull()
    }

    private fun openFrom(peerPub: String, enc: JSONObject?): JSONObject? {
        if (enc == null) return null
        val shared = keyPair?.let { ChatCrypto.sharedKey(it.private, myPub(), peerPub) } ?: return null
        return ChatCrypto.open(shared, enc)
    }

    private fun handlePayload(body: JSONObject, remoteIp: String = ""): JSONObject {
        val from = body.optJSONObject("from") ?: JSONObject()
        val action = body.optString("action")
        val peerPub = from.optString("pub")
        return when (action) {
            "ping" -> JSONObject()
                .put("ok", true)
                .put("app", "devtoolbox-chat")
                .put("profile", myBrief())
            "profile" -> {
                rememberPeer(from)
                JSONObject().put("ok", true)
            }
            "deliver" -> {
                val payload = openFrom(peerPub, body.optJSONObject("enc")) ?: body.optJSONObject("payload")
                if (payload == null) JSONObject().put("ok", false).put("error", "bad payload")
                else {
                    receiveMessage(from, payload)
                    JSONObject().put("ok", true)
                }
            }
            "friend-request" -> {
                val payload = openFrom(peerPub, body.optJSONObject("enc")) ?: body.optJSONObject("payload")
                if (payload == null) JSONObject().put("ok", false)
                else {
                    receiveRequest(from, payload, remoteIp)
                    JSONObject().put("ok", true)
                }
            }
            "friend-request-result" -> {
                val payload = openFrom(peerPub, body.optJSONObject("enc")) ?: body.optJSONObject("payload")
                if (payload == null) JSONObject().put("ok", false)
                else {
                    applyRequestResult(from, payload)
                    JSONObject().put("ok", true)
                }
            }
            else -> JSONObject().put("ok", false).put("error", "unknown action")
        }
    }

    private fun rememberPeer(from: JSONObject) {
        val peerId = from.optString("id").ifEmpty { return }
        val index = store.friends.indexOfFirst { it.id == peerId }
        if (index >= 0) {
            val friend = store.friends[index]
            store.friends[index] = friend.copy(
                name = from.optString("nickname").ifEmpty { friend.name },
                avatar = from.optString("avatar").ifEmpty { friend.avatar },
                pub = from.optString("pub").ifEmpty { friend.pub },
                lastSeenAt = System.currentTimeMillis(),
            )
            store.save()
            publish()
        }
    }

    /** ping：拿到对方资料（添加好友、刷新在线状态都用它） */
    private fun ping(ip: String, port: Int): Triple<JSONObject?, String, String> {
        val body = JSONObject().put("action", "ping").put("from", myBrief())
        val result = ChatNet.post(ip, port, body) ?: return Triple(null, "", "")
        val json = result.first ?: return Triple(null, result.second, "")
        if (!json.optBoolean("ok")) return Triple(null, result.second, "")
        return Triple(json.optJSONObject("profile"), result.second, "")
    }

    fun addFriend(rawAddress: String): String? {
        if (!ChatCrypto.isSupported) return "需要 Android 13 及以上才能加密聊天"
        val address = rawAddress.trim()
        if (address.isEmpty()) return "请填写对方的 地址:端口，例如 192.168.1.8:45871"
        val ip = address.substringBeforeLast(':', address)
        val port = address.substringAfterLast(':', "45871").toIntOrNull() ?: 45871
        val (profile, certPrint, _) = ping(ip, port)
        if (profile == null) return "连不上 $address（对方没开本地聊天，或网络不通）"
        val peerId = profile.optString("id")
        if (peerId.isEmpty()) return "对方返回的数据不完整"
        if (peerId == store.me.id) return "这是你自己的地址"
        val peerPub = profile.optString("pub")
        if (peerPub.isEmpty()) return "对方版本过旧（没有加密公钥）"

        val existing = store.friends.firstOrNull { it.id == peerId }
            ?: store.friends.firstOrNull { it.ip == ip && it.port == port }
        if (existing != null) {
            store.upsertFriend(
                existing.copy(
                    name = profile.optString("nickname").ifEmpty { existing.name },
                    avatar = profile.optString("avatar").ifEmpty { existing.avatar },
                    pub = peerPub,
                    certPrint = existing.certPrint.ifEmpty { certPrint },
                    lastSeenAt = System.currentTimeMillis(),
                ),
            )
            store.save()
            publish()
            return null
        }

        val requestId = "req-" + UUID.randomUUID().toString().take(8)
        val request = ChatRequest(
            id = requestId,
            requestId = requestId,
            name = profile.optString("nickname"),
            avatar = profile.optString("avatar"),
            ip = ip,
            port = port,
            pub = peerPub,
            message = "",
            outgoing = true,
            at = System.currentTimeMillis(),
        )
        store.requests.add(request)
        store.save()
        publish()

        val payload = JSONObject().put("requestId", requestId).put("remark", "")
        val enc = sealFor(peerPub, payload)
        val body = JSONObject().put("action", "friend-request").put("from", myBrief())
        if (enc != null) body.put("enc", enc) else body.put("payload", payload)
        val reply = ChatNet.post(ip, port, body)
        return if (reply?.first != null) null else "申请已记录，但对方没收到（对方可能没开聊天）"
    }

    private fun receiveRequest(from: JSONObject, payload: JSONObject, remoteIp: String = "") {
        val peerId = from.optString("id")
        val requestId = payload.optString("requestId")
        if (peerId.isNotEmpty() && store.friends.any { it.id == peerId }) {
            // 已经是好友：直接回「同意」，省得对方一直等
            scope.launch { replyRequest(from, requestId, true) }
            return
        }
        store.requests.removeAll { it.requestId == requestId && !it.outgoing }
        Notify.chatRequest(appContext, from.optString("nickname"))
        store.requests.add(
            ChatRequest(
                id = requestId.ifEmpty { "req-" + UUID.randomUUID().toString().take(8) },
                requestId = requestId,
                name = from.optString("nickname"),
                avatar = from.optString("avatar"),
                // 回连地址：对方报了 host 就用它，否则用这次连接的来源 IP（和 PC 端一致）
                ip = from.optString("host").takeIf { it.isNotEmpty() && it != "127.0.0.1" && it != "::1" }
                    ?: remoteIp.ifEmpty { ChatNet.localAddresses().firstOrNull() ?: "" },
                port = from.optInt("port", 45871),
                pub = from.optString("pub"),
                message = payload.optString("remark"),
                outgoing = false,
                at = System.currentTimeMillis(),
            ),
        )
        store.save()
        publish()
    }

    fun respondRequest(requestId: String, accepted: Boolean) {
        val request = store.requests.firstOrNull { it.id == requestId } ?: return
        if (accepted && !request.outgoing) {
            store.upsertFriend(
                ChatFriend(
                    id = "friend-" + UUID.randomUUID().toString().take(8),
                    name = request.name,
                    avatar = request.avatar,
                    ip = request.ip,
                    port = request.port,
                    pub = request.pub,
                    lastSeenAt = System.currentTimeMillis(),
                ),
            )
        }
        store.requests.removeAll { it.id == requestId }
        store.save()
        publish()

        // 回执：对方按 requestId 记住我
        val from = JSONObject()
            .put("id", store.me.id)
            .put("nickname", store.me.name)
            .put("avatar", store.me.avatar)
            .put("port", store.me.port)
            .put("pub", myPub())
            .put("host", ChatNet.localAddresses().firstOrNull() ?: "")
        scope.launch { replyRequest(from, request.requestId.ifEmpty { request.id }, accepted, request.ip, request.port, request.pub) }
    }

    private fun replyRequest(
        from: JSONObject,
        requestId: String,
        accepted: Boolean,
        ip: String = "",
        port: Int = 0,
        peerPub: String = "",
    ) {
        val targetIp = ip.ifEmpty { from.optString("host") }
        val targetPort = if (port > 0) port else from.optInt("port", 45871)
        if (targetIp.isEmpty()) return
        val payload = JSONObject().put("requestId", requestId).put("accepted", accepted)
        val enc = peerPub.takeIf { it.isNotEmpty() }?.let { sealFor(it, payload) }
        val body = JSONObject().put("action", "friend-request-result").put("from", myBrief())
        if (enc != null) body.put("enc", enc) else body.put("payload", payload)
        ChatNet.post(targetIp, targetPort, body)
    }

    private fun applyRequestResult(from: JSONObject, payload: JSONObject) {
        val requestId = payload.optString("requestId")
        val accepted = payload.optBoolean("accepted")
        val request = store.requests.firstOrNull { it.outgoing && (it.requestId == requestId || it.id == requestId) }
        if (accepted) {
            val peerId = from.optString("id").ifEmpty { request?.id ?: "" }
            store.upsertFriend(
                ChatFriend(
                    id = "friend-" + UUID.randomUUID().toString().take(8),
                    name = from.optString("nickname").ifEmpty { request?.name ?: "好友" },
                    avatar = from.optString("avatar").ifEmpty { request?.avatar ?: "" },
                    ip = request?.ip ?: from.optString("host"),
                    port = if (from.optInt("port", 0) > 0) from.optInt("port") else (request?.port ?: 45871),
                    pub = from.optString("pub").ifEmpty { request?.pub ?: "" },
                    lastSeenAt = System.currentTimeMillis(),
                ),
            )
        }
        if (request != null) store.requests.removeAll { it.id == request.id }
        if (!accepted && peerIdOrNull(from).isEmpty() && request != null) store.requests.removeAll { it.id == request.id }
        store.save()
        publish()
    }

    private fun peerIdOrNull(from: JSONObject): String = from.optString("id")

    // ------------------------------------------------------------------
    // 消息
    // ------------------------------------------------------------------

    fun sendText(friendId: String, text: String): String? {
        val friend = store.friend(friendId) ?: return "好友不存在"
        val body = text.trim()
        if (body.isEmpty()) return "消息不能为空"
        if (friend.pub.isEmpty()) return "还没有对方的公钥，请重新添加好友"

        val message = ChatMessage(
            id = "m-" + UUID.randomUUID().toString().take(8),
            friendId = friendId,
            outgoing = true,
            text = body,
            at = System.currentTimeMillis(),
        )
        store.addMessage(message)
        store.upsertFriend(friend.copy(lastMessage = ChatStore.preview(body), lastMessageAt = message.at))
        store.save()
        publish()

        val payload = JSONObject().put("kind", "text").put("text", body)
        val enc = sealFor(friend.pub, payload) ?: return "加密失败（对方公钥不对）"
        val request = JSONObject().put("action", "deliver").put("from", myBrief()).put("enc", enc)
        scope.launch {
            val result = ChatNet.post(friend.ip, friend.port, request)
            if (result == null) {
                store.upsertFriend(friend.copy(lastMessage = "$body（对方未在线）", lastMessageAt = message.at))
                store.save()
                publish()
            } else {
                val print = result.second
                if (print.isNotEmpty() && friend.certPrint.isEmpty()) {
                    store.upsertFriend(friend.copy(certPrint = print))
                    store.save()
                    publish()
                }
            }
        }
        return null
    }

    private fun receiveMessage(from: JSONObject, payload: JSONObject) {
        val text = payload.optString("text")
        if (text.isEmpty()) return
        // 有的客户端会把图片 / 头像的 data URL 塞进文本字段，这里直接丢弃
        if (text.startsWith("data:") || text.length > 20000) return
        val peerPub = from.optString("pub")
        val friendId = store.friends.firstOrNull { it.pub == peerPub }?.id
            ?: store.friends.firstOrNull { it.ip == from.optString("host") }?.id
            ?: from.optString("id")
        val message = ChatMessage(
            id = "m-" + UUID.randomUUID().toString().take(8),
            friendId = friendId,
            outgoing = false,
            text = text,
            at = System.currentTimeMillis(),
        )
        store.addMessage(message)
        val existing = store.friend(friendId)
        if (currentFriendId != friendId) {
            Notify.chatMessage(appContext, existing?.name ?: from.optString("nickname"), text)
        }
        if (existing != null) {
            val unread = if (currentFriendId == existing.id) 0 else existing.unread + 1
            store.upsertFriend(
                existing.copy(
                    lastMessage = ChatStore.preview(text),
                    lastMessageAt = message.at,
                    lastSeenAt = System.currentTimeMillis(),
                    unread = unread,
                ),
            )
        }
        store.save()
        publish()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(60_000)
                store.friends.forEach { friend ->
                    runCatching { ping(friend.ip, friend.port) }
                }
            }
        }
    }

    fun removeFriend(friendId: String) {
        store.friends.removeAll { it.id == friendId }
        store.messages.removeAll { it.friendId == friendId }
        store.save()
        publish()
    }

    fun dismissRequest(requestId: String) {
        store.requests.removeAll { it.id == requestId }
        store.save()
        publish()
    }

    private fun publish() {
        _state.value = ChatUiState(
            me = store.me,
            friends = store.friends.sortedByDescending { it.lastMessageAt },
            requests = store.requests.sortedByDescending { it.at },
            messages = store.messages,
            listening = server != null,
            supported = ChatCrypto.isSupported,
            error = _state.value.error,
            addresses = ChatNet.localAddresses(),
        )
    }

    fun messagesOf(friendId: String): List<ChatMessage> = store.messagesOf(friendId)
}
