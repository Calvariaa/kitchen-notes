package io.github.Calvariaa.kitchennotes.sync

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64 as JBase64

/**
 * SSH 密钥的生成与 OpenSSH 格式编码。
 *
 * 用 JCA 生成 RSA-2048，然后手工拼 OpenSSH 的两种格式：
 * - 公钥：`ssh-rsa <base64(blob)> <comment>`
 * - 私钥：PEM 包裹的 `OpenSSH PRIVATE KEY` 块
 *
 * Android 上没有 `ssh-keygen`，所以这两段编码得自己写。
 * 算法照 RFC4253 / OpenSSH PROTOCOL.key。解析侧见 [SshKeyReader]。
 */
object SshKeyFactory {

    fun generateKeyPair(email: String): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }
        val kp = kpg.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        val priv = kp.private as RSAPrivateCrtKey
        val privatePem = encodeOpenSshPrivate(priv, comment = email)
        return privatePem to encodeOpenSshPublic(pub, comment = email)
    }

    /**
     * 按 OpenSSH 规范算公钥指纹：`SHA256:` + 无填充 base64(sha256(blob))。
     *
     * 不能截取公钥文本当指纹 —— `ssh-rsa AAAAB3NzaC1yc2EAAA` 开头是协议常量、
     * 结尾是注释（邮箱），都与密钥字节无关。重新生成密钥后截首尾的「指纹」
     * 一个字符都不会变，用户会以为刷新没生效。
     *
     * 这个值同时是 P2P 里的 `nodeId`：全网唯一、与 IP 和显示名无关。
     */
    fun fingerprint(publicKey: String): String {
        val b64 = publicKey.trim().split(Regex("\\s+")).getOrNull(1) ?: return ""
        val blob = runCatching { JBase64.getDecoder().decode(b64) }.getOrNull() ?: return ""
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + JBase64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun encodeOpenSshPublic(pub: RSAPublicKey, comment: String): String {
        val blob = ByteArrayBuilder()
            .writeString("ssh-rsa")
            .writeMpInt(pub.publicExponent.toByteArray())
            .writeMpInt(pub.modulus.toByteArray())
            .build()
        return "ssh-rsa ${JBase64.getEncoder().encodeToString(blob)} $comment"
    }

    private fun encodeOpenSshPrivate(priv: RSAPrivateCrtKey, comment: String): String {
        // OpenSSH private key v1 (unencrypted)
        val magic = "openssh-key-v1".toByteArray()

        val publicBlob = ByteArrayBuilder()
            .writeString("ssh-rsa")
            .writeMpInt(priv.publicExponent.toByteArray())
            .writeMpInt(priv.modulus.toByteArray())
            .build()

        val privateBlob = ByteArrayBuilder()
            .writeInt(0x12345678) // checkint 1
            .writeInt(0x12345678) // checkint 2
            .writeString("ssh-rsa")
            .writeMpInt(priv.modulus.toByteArray())
            .writeMpInt(priv.publicExponent.toByteArray())
            .writeMpInt(priv.privateExponent.toByteArray())
            .writeMpInt(priv.crtCoefficient.toByteArray())
            .writeMpInt(priv.primeP.toByteArray())
            .writeMpInt(priv.primeQ.toByteArray())
            .writeString(comment)
            .build()
        // padding 使总长为 8 的倍数
        val padded = privateBlob.copyOf(
            privateBlob.size + ((8 - privateBlob.size % 8) % 8),
        ).also { arr ->
            var pad = 1
            for (i in privateBlob.size until arr.size) arr[i] = pad++.toByte()
        }

        val body = ByteArrayBuilder()
            .writeBytes(magic)
            .writeByte(0)
            .writeString("none")   // cipher
            .writeString("none")   // kdf
            .writeString("")       // kdf options
            .writeInt(1)           // number of keys
            .writeString(publicBlob)
            .writeString(padded)
            .build()

        val b64 = JBase64.getEncoder().encode(body)
        val wrapped = b64.toString(Charsets.US_ASCII).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$wrapped\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    /** SSH 二进制协议用的便捷写手 */
    private class ByteArrayBuilder {
        private val out = java.io.ByteArrayOutputStream()

        fun writeByte(b: Int) = apply { out.write(b) }
        fun writeInt(v: Int) = apply {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        fun writeBytes(b: ByteArray) = apply { out.write(b) }

        fun writeString(s: String) = writeString(s.toByteArray(Charsets.UTF_8))

        fun writeString(b: ByteArray) = apply {
            writeInt(b.size)
            out.write(b)
        }

        /** mpint：正数如果最高位是 1 要补一个 0x00 */
        fun writeMpInt(v: ByteArray) = apply {
            var bytes = v
            while (bytes.size > 1 && bytes[0] == 0.toByte() && bytes[1] >= 0) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }
            if (bytes.isNotEmpty() && bytes[0] < 0) bytes = byteArrayOf(0) + bytes
            writeString(bytes)
        }

        fun build(): ByteArray = out.toByteArray()
    }
}
