package com.android_tools.app.sync

import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 桌面端清单里的一个文件。 */
data class RemoteFile(
    val path: String,
    val size: Long,
    val mtime: Long,
)

/** `/sync/list` 的返回。 */
data class RemoteManifest(
    val version: String,
    val files: List<RemoteFile>,
)

/** 同步日志里的一条记录。 */
data class SyncLogEntry(
    val at: Long,
    val text: String,
)

/**
 * 极简的同步客户端：只依赖 `HttpURLConnection`，不引第三方库。
 *
 * 协议（与桌面端 DevToolbox 的「文件同步」一致）：
 * - `POST /sync/hello`   握手，登记自己
 * - `GET  /sync/list`    取清单 `{ version, files: [{path,size,mtime}] }`
 * - `GET  /sync/download?path=` 取文件字节
 * - `POST /sync/upload?path=&device=` 上传文件字节
 */
class SyncClient(baseAddress: String) {

    private val base: String = normalize(baseAddress)

    private fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    val isConfigured: Boolean get() = base.isNotEmpty()

    fun hello(deviceId: String, name: String): Boolean {
        val body = JSONObject()
            .put("id", deviceId)
            .put("name", name)
            .put("platform", "Android")
            .toString()
        val connection = open("/sync/hello", "POST") ?: return false
        return try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            code in 200..299
        } catch (error: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    fun list(): RemoteManifest? {
        val connection = open("/sync/list", "GET") ?: return null
        return try {
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val array = json.optJSONArray("files") ?: return null
            val files = ArrayList<RemoteFile>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val path = item.optString("path")
                if (path.isEmpty()) continue
                files += RemoteFile(
                    path = path,
                    size = item.optLong("size"),
                    mtime = item.optLong("mtime"),
                )
            }
            RemoteManifest(version = json.optString("version"), files = files)
        } catch (error: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun download(
        path: String,
        onProgress: ((Long, Long) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        onPeerCancelled: () -> Unit = {},
    ): ByteArray? {
        val encoded = URLEncoder.encode(path, "UTF-8")
        val connection = open("/sync/download?path=$encoded", "GET") ?: return null
        return try {
            if (connection.responseCode == 409) {
                // 电脑端取消了该文件，本次同步不再重试
                onPeerCancelled()
                null
            } else if (connection.responseCode !in 200..299) {
                null
            } else {
                val total = connection.contentLengthLong.coerceAtLeast(0L)
                val buffer = ByteArray(16 * 1024)
                val out = java.io.ByteArrayOutputStream()
                BufferedInputStream(connection.inputStream).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        onProgress?.invoke(out.size().toLong(), total)
                        if (isCancelled()) {
                            connection.disconnect()
                            return null
                        }
                    }
                }
                out.toByteArray()
            }
        } catch (error: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun upload(
        path: String,
        bytes: ByteArray,
        deviceId: String,
        onProgress: ((Long, Long) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        onPeerCancelled: () -> Unit = {},
    ): Boolean {
        val encodedPath = URLEncoder.encode(path, "UTF-8")
        val encodedDevice = URLEncoder.encode(deviceId, "UTF-8")
        val connection = open("/sync/upload?path=$encodedPath&device=$encodedDevice", "POST") ?: return false
        return try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { output ->
                val buffer = 16 * 1024
                var offset = 0
                while (offset < bytes.size) {
                    val length = minOf(buffer, bytes.size - offset)
                    output.write(bytes, offset, length)
                    offset += length
                    onProgress?.invoke(offset.toLong(), bytes.size.toLong())
                    if (isCancelled()) {
                        connection.disconnect()
                        return false
                    }
                }
            }
            val code = connection.responseCode
            if (code == 409) onPeerCancelled()
            code in 200..299
        } catch (error: Exception) {
            // 电脑端可能直接回 409（已取消）并关闭连接：尽量读一下状态码
            val code = runCatching { connection.responseCode }.getOrNull()
            if (code == 409) onPeerCancelled()
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection? {
        if (base.isEmpty()) return null
        return try {
            val connection = URL("$base$path").openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = TimeUnit.SECONDS.toMillis(6).toInt()
            // 传输中每隔一小段就有数据；10 秒没动静说明连接已经断了
            connection.readTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
            connection.setRequestProperty("Accept", "application/json")
            connection
        } catch (error: Exception) {
            null
        }
    }
}
