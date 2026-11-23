package com.android_tools.app.chat

import android.content.Context

/**
 * 聊天运行时：本地聊天的引擎放在进程级，而不是绑在「本地聊天」页面上。
 *
 * 这样从聊天页进入「聊天设置」、或者在好友列表和对话页之间切换时，
 * 监听服务不会停，对方的申请 / 消息也不会丢。
 */
object ChatRuntime {
    private var instance: ChatEngine? = null

    fun engine(context: Context): ChatEngine =
        instance ?: ChatEngine(context.applicationContext).also { instance = it }

    /** 进程退出前收尾（目前只有测试用得到） */
    fun shutdown() {
        runCatching { instance?.shutdown() }
        instance = null
    }
}
