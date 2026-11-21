package com.screenrecorder.app.sync

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

    fun download(path: String): ByteArray? {
        val encoded = URLEncoder.encode(path, "UTF-8")
        val connection = open("/sync/download?path=$encoded", "GET") ?: return null
        return try {
            if (connection.responseCode !in 200..299) null
            else BufferedInputStream(connection.inputStream).use { it.readBytes() }
        } catch (error: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun upload(path: String, bytes: ByteArray, deviceId: String): Boolean {
        val encodedPath = URLEncoder.encode(path, "UTF-8")
        val encodedDevice = URLEncoder.encode(deviceId, "UTF-8")
        val connection = open("/sync/upload?path=$encodedPath&device=$encodedDevice", "POST") ?: return false
        return try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            connection.responseCode in 200..299
        } catch (error: Exception) {
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
            connection.readTimeout = TimeUnit.SECONDS.toMillis(20).toInt()
            connection.setRequestProperty("Accept", "application/json")
            connection
        } catch (error: Exception) {
            null
        }
    }
}
