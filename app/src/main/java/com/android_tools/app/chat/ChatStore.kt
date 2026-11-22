package com.android_tools.app.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 好友资料 */
data class ChatFriend(
    val id: String,
    val name: String,
    val avatar: String,
    val ip: String,
    val port: Int,
    /** 对方的 X25519 公钥（base64url，SPKI），用于消息加密 */
    val pub: String,
    val lastSeenAt: Long = 0,
    /** 对方 HTTPS 证书指纹（首次连上时记住） */
    val certPrint: String = "",
    val lastMessage: String = "",
    val lastMessageAt: Long = 0,
    val unread: Int = 0,
)

/** 一条消息 */
data class ChatMessage(
    val id: String,
    val friendId: String,
    val outgoing: Boolean,
    val text: String,
    val at: Long,
)

/** 好友申请 */
data class ChatRequest(
    val id: String,
    val name: String,
    val avatar: String,
    val ip: String,
    val port: Int,
    val pub: String,
    val message: String,
    /** 双方的申请 id（PC 端叫 remoteId），同意 / 拒绝时回传 */
    val requestId: String = "",
    val outgoing: Boolean,
    val at: Long,
    val accepted: Boolean? = null,
)

/** 我的资料 */
data class ChatMe(
    val id: String,
    val name: String,
    val avatar: String,
    val port: Int,
)

/**
 * 聊天数据（本地 JSON 文件）。结构参考 PC 端的 chat.json，字段做了精简。
 */
class ChatStore(private val file: File) {
    var me: ChatMe = ChatMe(
        id = "u-" + UUID.randomUUID().toString().take(8),
        name = randomNickname(),
        avatar = randomAvatar(),
        port = 45900,
    )
        private set
    val friends = ArrayList<ChatFriend>()
    val messages = ArrayList<ChatMessage>()
    val requests = ArrayList<ChatRequest>()

    fun load() {
        if (!file.exists()) return
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        root.optJSONObject("me")?.let { json ->
            me = ChatMe(
                id = json.optString("id").ifEmpty { me.id },
                name = json.optString("name").ifEmpty { me.name },
                avatar = json.optString("avatar").ifEmpty { me.avatar },
                port = json.optInt("port", me.port),
            )
        }
        friends.clear()
        root.optJSONArray("friends")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                friends += ChatFriend(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    avatar = item.optString("avatar"),
                    ip = item.optString("ip"),
                    port = item.optInt("port"),
                    pub = item.optString("pub"),
                    lastSeenAt = item.optLong("lastSeenAt"),
                    certPrint = item.optString("certPrint"),
                    lastMessage = preview(item.optString("lastMessage")),
                    lastMessageAt = item.optLong("lastMessageAt"),
                    unread = item.optInt("unread"),
                )
            }
        }
        messages.clear()
        root.optJSONArray("messages")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                messages += ChatMessage(
                    id = item.optString("id"),
                    friendId = item.optString("friendId"),
                    outgoing = item.optBoolean("outgoing"),
                    text = item.optString("text"),
                    at = item.optLong("at"),
                )
            }
        }
        requests.clear()
        root.optJSONArray("requests")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                requests += ChatRequest(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    avatar = item.optString("avatar"),
                    ip = item.optString("ip"),
                    port = item.optInt("port"),
                    pub = item.optString("pub"),
                    message = item.optString("message"),
                    requestId = item.optString("remoteId"),
                    outgoing = item.optBoolean("outgoing"),
                    at = item.optLong("at"),
                    accepted = if (item.has("accepted") && !item.isNull("accepted")) item.optBoolean("accepted") else null,
                )
            }
        }
    }

    fun save() {
        val root = JSONObject()
        root.put(
            "me",
            JSONObject()
                .put("id", me.id)
                .put("name", me.name)
                .put("avatar", me.avatar)
                .put("port", me.port),
        )
        val friendArray = JSONArray()
        for (f in friends) {
            friendArray.put(
                JSONObject()
                    .put("id", f.id).put("name", f.name).put("avatar", f.avatar)
                    .put("ip", f.ip).put("port", f.port).put("pub", f.pub)
                    .put("lastSeenAt", f.lastSeenAt).put("certPrint", f.certPrint)
                    .put("lastMessage", preview(f.lastMessage))
                    .put("lastMessageAt", f.lastMessageAt).put("unread", f.unread),
            )
        }
        root.put("friends", friendArray)
        val msgArray = JSONArray()
        for (m in messages) {
            msgArray.put(
                JSONObject().put("id", m.id).put("friendId", m.friendId)
                    .put("outgoing", m.outgoing).put("text", m.text).put("at", m.at),
            )
        }
        root.put("messages", msgArray)
        val reqArray = JSONArray()
        for (r in requests) {
            reqArray.put(
                JSONObject().put("id", r.id).put("name", r.name).put("avatar", r.avatar)
                    .put("ip", r.ip).put("port", r.port).put("pub", r.pub)
                    .put("message", r.message).put("remoteId", r.requestId)
                    .put("outgoing", r.outgoing).put("at", r.at)
                    .put("accepted", r.accepted ?: JSONObject.NULL),
            )
        }
        root.put("requests", reqArray)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        }
    }

    fun updateMe(name: String, avatar: String, port: Int) {
        me = me.copy(name = name, avatar = avatar, port = port)
    }

    fun friend(id: String): ChatFriend? = friends.firstOrNull { it.id == id }

    fun upsertFriend(friend: ChatFriend) {
        val index = friends.indexOfFirst { it.id == friend.id }
        if (index >= 0) friends[index] = friend else friends.add(friend)
    }

    fun messagesOf(friendId: String): List<ChatMessage> = messages.filter { it.friendId == friendId }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        if (messages.size > 2000) messages.subList(0, messages.size - 2000).clear()
    }

    companion object {
        private val NICK_WORDS = listOf(
            "安静的", "好奇的", "爱笑的", "慢热的", "认真的", "打盹的", "爱吃的", "靠谱的",
        )
        private val NICK_ANIMALS = listOf(
            "松鼠", "水獭", "海豚", "狐狸", "考拉", "企鹅", "刺猬", "猫头鹰",
        )
        val AVATARS = listOf("🐳", "🦊", "🐨", "🐼", "🐧", "🦉", "🐙", "🐝", "🌵", "🍀", "🚀", "🎧")

        private const val MAX_PREVIEW = 200

        fun randomNickname(): String = NICK_WORDS.random() + NICK_ANIMALS.random()

        fun randomAvatar(): String = AVATARS.random()

        /** 消息预览：data: 开头的（头像 / 图片）和超长内容不直接展示 */
        fun preview(text: String): String {
            val value = text.trim()
            if (value.isEmpty()) return ""
            if (value.startsWith("data:")) return "（图片）"
            if (value.length > MAX_PREVIEW) return value.take(MAX_PREVIEW) + "…"
            return value.lineSequence().first().take(MAX_PREVIEW)
        }

        /** 头像：PC 端头像可能是 data URL，安卓端先用一个图标代替 */
        fun avatarOf(avatar: String): String {
            val value = avatar.trim()
            return if (value.isEmpty() || value.startsWith("data:")) "🙂" else value
        }
    }
}
