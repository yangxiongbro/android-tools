package com.screenrecorder.app.sync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * 蓝牙（RFCOMM / 串口协议）传输文件。
 *
 * 协议与「蓝牙分享」常见的做法一致：先发一行 JSON 头（文件名 + 字节数），再发原始字节。
 * 手机与手机之间两两配对后即可互传，不依赖 WiFi。
 */
class BluetoothSync(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null

    val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isAvailable(): Boolean = adapter?.isEnabled == true

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        return connect == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        if (!hasPermission()) return emptyList()
        return runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
    }

    /** 发一个文件给已配对的设备 */
    fun send(
        device: BluetoothDevice,
        fileName: String,
        bytes: ByteArray,
        onDone: (Boolean, String) -> Unit,
    ) {
        scope.launch {
            var socket: BluetoothSocket? = null
            try {
                // 连接前先停掉扫描：扫描进行中连接会失败（read ret: -1）
                runCatching { adapter?.cancelDiscovery() }
                socket = connectWithFallback(device)
                val output = DataOutputStream(socket.outputStream)
                val header = JSONObject()
                    .put("name", fileName)
                    .put("size", bytes.size)
                    .toString()
                output.writeUTF(header)
                output.write(bytes)
                output.flush()
                onDone(true, "已发送 $fileName（${bytes.size / 1024} KB）")
            } catch (error: Exception) {
                onDone(false, "发送失败：${error.message ?: error.javaClass.simpleName}（请确认电脑端点了「接收一个文件」、双方已配对）")
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    /**
     * 连接失败时依次尝试三种常见方式：
     * 1. 标准 SPP（服务记录里的通道）；
     * 2. 通道 1 —— Windows / 部分车载与廉价设备只认这个；
     * 3. 不加密的 SPP（配对密钥有问题时能绕过）。
     */
    private fun connectWithFallback(device: BluetoothDevice): BluetoothSocket {
        var lastError: Exception? = null

        fun attempt(factory: () -> BluetoothSocket): BluetoothSocket? {
            return try {
                val candidate = factory()
                candidate.connect()
                candidate
            } catch (error: Exception) {
                lastError = error
                null
            }
        }

        attempt { device.createRfcommSocketToServiceRecord(SPP_UUID) }?.let { return it }
        attempt {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, 1) as BluetoothSocket
        }?.let { return it }
        attempt { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }?.let { return it }

        throw lastError ?: java.io.IOException("无法连接蓝牙设备")
    }

    /**
     * 开始接收：监听 RFCOMM，收到文件后交给 [onFile] 落盘。
     * 返回 false 表示蓝牙不可用或没有权限。
     */
    fun startReceive(onFile: (name: String, bytes: ByteArray) -> Unit, onLog: (String) -> Unit): Boolean {
        if (!isAvailable() || !hasPermission()) return false
        acceptJob?.cancel()
        acceptJob = scope.launch {
            var serverSocket: BluetoothServerSocket? = null
            try {
                serverSocket = adapter?.listenUsingRfcommWithServiceRecord("DevToolbox Sync", SPP_UUID)
                onLog("等待蓝牙文件…")
                while (isActive) {
                    val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: break
                    launch { handleIncoming(socket, onFile, onLog) }
                }
            } catch (error: Exception) {
                onLog("蓝牙接收失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                runCatching { serverSocket?.close() }
            }
        }
        return true
    }

    fun stopReceive() {
        acceptJob?.cancel()
        acceptJob = null
    }

    fun shutdown() {
        stopReceive()
        scope.cancel()
    }

    private fun handleIncoming(
        socket: BluetoothSocket,
        onFile: (String, ByteArray) -> Unit,
        onLog: (String) -> Unit,
    ) {
        try {
            val input = DataInputStream(socket.inputStream)
            val header = JSONObject(input.readUTF())
            val name = header.optString("name", "bluetooth-file")
            val size = header.optInt("size", 0)
            if (size <= 0 || size > MAX_BYTES) {
                onLog("收到非法文件大小：$size")
                return
            }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            onFile(name, bytes)
            onLog("收到 $name（${size / 1024} KB）")
        } catch (error: Exception) {
            onLog("接收失败：${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        /** 标准串口（SPP）UUID，安卓 / Windows 通用 */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val MAX_BYTES = 64 * 1024 * 1024
    }
}
