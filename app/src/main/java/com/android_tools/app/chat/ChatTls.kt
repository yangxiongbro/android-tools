package com.android_tools.app.chat

import android.content.Context
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 聊天用的 TLS：
 * - 服务端用打包在 assets 里的自签名证书（PC 端首次连接会记住它的指纹，之后变化会提示）；
 * - 客户端不校验 CA（都是自签名的），由上层用「证书指纹」做 TOFU 校验。
 */
object ChatTls {
    private const val PASSWORD = "android-tools"

    /** 服务端 SSLContext（带私钥） */
    fun serverContext(context: Context): SSLContext? = runCatching {
        val keyStore = KeyStore.getInstance("PKCS12")
        context.assets.open("chat.p12").use { stream -> keyStore.load(stream, PASSWORD.toCharArray()) }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, PASSWORD.toCharArray())
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(factory.keyManagers, null, SecureRandom())
        ssl
    }.getOrNull()

    private val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val hostnameVerifier: HostnameVerifier = HostnameVerifier { _, _ -> true }

    /** 客户端 SSLContext：信任一切自签名证书 */
    fun clientContext(): SSLContext? = runCatching {
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        ssl
    }.getOrNull()

    fun clientSocketFactory(): SSLSocketFactory? = clientContext()?.socketFactory
}
