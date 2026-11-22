package com.android_tools.app.chat

import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 与 PC 端完全一致的端到端加密：
 *
 * - 身份密钥：X25519（SPKI / PKCS8，base64url），需要 Android 13+（API 33）
 * - 会话密钥：HKDF-SHA256(secret, salt = sorted([myPub, peerPub]).join("|"), info = "devtoolbox-chat-e2e-v1", 32)
 * - 内容加密：AES-256-GCM，AAD = "devtoolbox-chat-v1"，信封 { iv, tag, data } 都是 base64
 */
object ChatCrypto {
    private const val INFO = "devtoolbox-chat-e2e-v1"
    private const val AAD = "devtoolbox-chat-v1"
    private const val TAG_BYTES = 16

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("XDH")
        // Android 的 Conscrypt 不支持 NamedParameterSpec，XDH 只接受密钥位数（255 = X25519）
        runCatching { generator.initialize(NamedParameterSpec("X25519")) }
            .onFailure { generator.initialize(255) }
        return generator.generateKeyPair()
    }

    fun encodePublic(key: PublicKey): String =
        Base64.encodeToString(key.encoded, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    fun decodePublic(text: String): PublicKey? = runCatching {
        if (text.isEmpty()) return null
        val bytes = Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(bytes))
    }.getOrNull()

    fun encodePrivate(key: PrivateKey): String =
        Base64.encodeToString(key.encoded, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    fun decodePrivate(text: String): PrivateKey? = runCatching {
        if (text.isEmpty()) return null
        val bytes = Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        KeyFactory.getInstance("XDH").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }.getOrNull()

    /** 与 PC 端 sharedKey(peerPub) 一致 */
    fun sharedKey(myPrivate: PrivateKey, myPub: String, peerPub: String): ByteArray? = runCatching {
        val peer = decodePublic(peerPub) ?: return null
        val agreement = KeyAgreement.getInstance("XDH")
        agreement.init(myPrivate)
        agreement.doPhase(peer, true)
        val secret = agreement.generateSecret()
        val salt = listOf(myPub, peerPub).sorted().joinToString("|").toByteArray(Charsets.UTF_8)
        hkdf(secret, salt, INFO.toByteArray(Charsets.UTF_8), 32)
    }.getOrNull()

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1)
        return mac.doFinal().copyOf(length)
    }

    /** 加密成和 PC 一样的信封（Android 的 doFinal 把 tag 拼在密文尾部，这里拆开） */
    fun seal(key: ByteArray, payload: JSONObject): JSONObject {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val combined = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        val data = combined.copyOfRange(0, combined.size - TAG_BYTES)
        val tag = combined.copyOfRange(combined.size - TAG_BYTES, combined.size)
        return JSONObject()
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("tag", Base64.encodeToString(tag, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
    }

    /** 解开对方的信封（{iv,tag,data}） */
    fun open(key: ByteArray, envelope: JSONObject): JSONObject? = runCatching {
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val tag = Base64.decode(envelope.getString("tag"), Base64.NO_WRAP)
        val data = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(data + tag)
        JSONObject(String(plain, Charsets.UTF_8))
    }.getOrNull()
}
