package com.android_tools.app.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 蓝牙同步在界面上的状态 */
data class BtUiState(
    val running: Boolean = false,
    /** 真正握手成功（收到过对方的帧）才算已连接 */
    val connected: Boolean = false,
    val device: String = "",
    val sent: Int = 0,
    val received: Int = 0,
    val transfer: TransferInfo? = null,
)

/**
 * 同步运行时：把 WiFi / 蓝牙的同步引擎放在进程级，而不是绑在「文件同步」页面上。
 *
 * 这样从同步页返回工具页、或者把应用切到后台时，传输都不会被打断；
 * 只有用户主动点「停止同步」才会停。
 */
object SyncRuntime {
    private var syncEngine: SyncEngine? = null
    private var bluetoothSync: BluetoothSync? = null
    private var btEngine: BtSyncEngine? = null

    private val _btState = MutableStateFlow(BtUiState())
    val btState: StateFlow<BtUiState> = _btState.asStateFlow()

    private val _btLogs = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val btLogs: StateFlow<List<Pair<Long, String>>> = _btLogs.asStateFlow()

    fun engine(context: Context): SyncEngine =
        syncEngine ?: SyncEngine(context.applicationContext).also { engine ->
            syncEngine = engine
            // 两个通道各注册一次取消实现，界面只调 TransferHub.cancel()
            TransferHub.register(TransferHub.Kind.WIFI) { engine.cancelTransfer() }
        }

    fun bluetooth(context: Context): BluetoothSync =
        bluetoothSync ?: BluetoothSync(context.applicationContext).also { bluetoothSync = it }

    fun bt(context: Context): BtSyncEngine {
        btEngine?.let { return it }
        val created = BtSyncEngine(
            engine = engine(context),
            onLog = { message ->
                _btLogs.value = (listOf(System.currentTimeMillis() to message) + _btLogs.value).take(200)
            },
            onState = { alive, device, sent, received, connected ->
                _btState.value = _btState.value.copy(
                    running = alive,
                    connected = connected,
                    device = device,
                    sent = sent,
                    received = received,
                )
            },
            onProgress = { info -> _btState.value = _btState.value.copy(transfer = info) },
        )
        btEngine = created
        TransferHub.register(TransferHub.Kind.BT) { created.cancelTransfer() }
        return created
    }

    /** 彻底收尾（目前只有进程退出会用到） */
    fun shutdown() {
        TransferHub.reset()
        runCatching { syncEngine?.stop() }
        runCatching { btEngine?.stop() }
        runCatching { bluetoothSync?.shutdown() }
        syncEngine = null
        btEngine = null
        bluetoothSync = null
    }
}
