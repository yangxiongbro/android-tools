package com.android_tools.app.chat

import android.content.Context
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * 聊天的网络层（与 PC 端一致）：
 * - 传输：HTTPS，自签名证书；客户端记住并校验对方证书指纹（TOFU）
 * - 接口：`POST /chat/api`，body 是 JSON：`{ action, from, payload | enc }`
 */
object ChatNet {
    /** 发一个请求，返回 (json, 对方证书指纹) */
    fun post(
        ip: String,
        port: Int,
        body: JSONObject,
        timeoutMs: Int = 8000,
    ): Pair<JSONObject?, String>? {
        return runCatching {
            val connection = URL("https://$ip:$port/chat/api").openConnection() as HttpsURLConnection
            ChatTls.clientSocketFactory()?.let { connection.sslSocketFactory = it }
            connection.hostnameVerifier = ChatTls.hostnameVerifier
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("X-Chat-App", "devtoolbox-chat")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val certPrint = runCatching {
                val cert = connection.serverCertificates?.firstOrNull() as? X509Certificate
                cert?.sha256Fingerprint() ?: ""
            }.getOrDefault("")

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            connection.disconnect()
            val json = runCatching { JSONObject(text) }.getOrNull()
            json to certPrint
        }.getOrNull()
    }

    private fun X509Certificate.sha256Fingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
        return digest.joinToString(":") { String.format("%02X", it) }
    }

    /** 启动 HTTPS 监听；每个请求交给 [handler] */
    fun serve(context: Context, port: Int, handler: (JSONObject, String) -> JSONObject): SSLServerSocket? {
        val sslContext = ChatTls.serverContext(context) ?: return null
        return runCatching {
            val server = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            Thread {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                    Thread { handleConnection(socket, handler) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            server
        }.getOrNull()
    }

    private fun handleConnection(socket: SSLSocket, handler: (JSONObject, String) -> JSONObject) {
        socket.use { client ->
            runCatching {
                client.soTimeout = 8000
                client.startHandshake()
                val input = client.inputStream
                val head = StringBuilder()
                while (head.length < 16 * 1024) {
                    val b = input.read()
                    if (b < 0) return
                    head.append(b.toChar())
                    if (head.endsWith("\r\n\r\n")) break
                }
                if (!head.contains("/chat/api")) {
                    respond(client, 404, JSONObject().put("ok", false))
                    return
                }
                val contentLength = Regex("(?i)content-length:\\s*(\\d+)")
                    .find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = input.read(bytes, read, contentLength - read)
                    if (count <= 0) break
                    read += count
                }
                val body = runCatching { JSONObject(String(bytes, 0, read, Charsets.UTF_8)) }.getOrNull()
                val remoteIp = client.inetAddress?.hostAddress ?: ""
                val reply = if (body == null) JSONObject().put("ok", false) else handler(body, remoteIp)
                respond(client, 200, reply)
            }.onFailure {
                runCatching { respond(client, 200, JSONObject().put("ok", false)) }
            }
        }
    }

    private fun respond(socket: SSLSocket, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.outputStream.apply {
            write(header.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
        runCatching { socket.shutdownOutput() }
    }

    fun localAddresses(): List<String> {
        val out = ArrayList<String>()
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                if (!iface.isUp || iface.isLoopback) return@forEach
                iface.inetAddresses.toList().forEach { address ->
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        out += address.hostAddress ?: ""
                    }
                }
            }
        }
        return out.filter { it.isNotEmpty() }.distinct()
    }

    fun timeout(millis: Long): Int = TimeUnit.MILLISECONDS.toMillis(millis).toInt()
}
