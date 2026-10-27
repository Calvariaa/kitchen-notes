package io.github.Calvariaa.kitchennotes.sync

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec

/**
 * 节点身份的密码学部分。
 *
 * **身份仍然是 SSH 密钥**（需求要求保留），但用法从「SSH 传输层认证」变成「签名」：
 *  - `nodeId` = SSH 公钥指纹（`SHA256:...`），全网唯一、改名字换邮箱都不变；
 *  - 每条对端请求用私钥签一段声明，对端用公钥验签。
 *
 * 为什么不用真的 SSH 协议：Android 上要跑完整 SSH 服务端需要 MINA sshd 或自写
 * 密钥交换/加密/通道复用，体积和风险都远超收益；而我们要的只是「证明我是我」，
 * RSA 签名已经足够。git 传输用 HTTP 是因为 JGit 两端原生支持，省掉一个协议栈。
 *
 * **两层证明，缺一不可**：
 *  - `sign` → 「我是持有这把私钥的节点」（身份，抗抵赖）
 *  - `groupTag` → 「我确实知道这个家的口令」（成员资格）
 * 第二个不能用签名代替：否则任何人拿自己新生成的密钥就能冒充新成员加入。
 */
object MeshCrypto {

    /** 允许的时钟偏差。设备间时间不同步是常态，5 分钟内都算新鲜。 */
    const val MAX_SKEW_MS = 5 * 60_000L

    fun randomNonce(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return b64(bytes, urlSafe = true)
    }

    /** `nodeId` 就是公钥指纹，和设置页展示的指纹是同一个值 */
    fun nodeId(publicKeyOpenSsh: String): String = SshKeyFactory.fingerprint(publicKeyOpenSsh)

    /**
     * 待签名字符串。
     *
     * 签的是**作用域**（`git` 或具体 path）而不是逐个 URL：JGit 一次 fetch 会自己发
     * `info/refs` 和 `upload-pack` 两个请求，客户端代码拿不到后者的路径，无法逐条签名。
     * 作用域 + 时间窗 + 服务端 nonce 缓存的组合，重放窗口已经被压到 5 分钟内且不可重复。
     */
    fun canonical(scope: String, timeMs: Long, nonce: String): ByteArray =
        "KN1\n$scope\n$timeMs\n$nonce".toByteArray(Charsets.UTF_8)

    fun sign(privateKeyPem: String, scope: String, timeMs: Long, nonce: String): String? =
        runCatching {
            val key = SshKeyReader.parsePrivateKey(privateKeyPem) ?: return null
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(key)
            sig.update(canonical(scope, timeMs, nonce))
            b64(sig.sign())
        }.getOrNull()

    fun verify(
        publicKeyOpenSsh: String,
        scope: String,
        timeMs: Long,
        nonce: String,
        signatureB64: String,
    ): Boolean = runCatching {
        val key = SshKeyReader.parsePublicKey(publicKeyOpenSsh) ?: return false
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initVerify(key)
        sig.update(canonical(scope, timeMs, nonce))
        sig.verify(Base64.decode(signatureB64, Base64.NO_WRAP))
    }.getOrDefault(false)

    // ------------------------------------------------------------ 成员资格

    /**
     * 成员的对外名字。由家庭密钥派生，成员之间一致、外部不可推。
     *
     * 不直接用 `secret` 做 HMAC 的 key 之外的用途：这个值会出现在
     * `/mesh/info` 的明文响应里，任何拿到它的人都不该能反推出 secret 或加入码。
     */
    fun groupTag(secret: String): String =
        hmachex(secret, "kn-group-tag").take(16)

    /**
     * 加入码：用户口头念给家人的那串。由 secret 派生，
     * 所以主机换人、重装 App 只要 secret 还在，码就不变。
     */
    fun inviteCode(secret: String): String {
        val mac = hmac(secret, "kn-invite")
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 去掉 I O 0 1，避免念错
        val sb = StringBuilder()
        for (i in 0 until 4) {
            val hi = mac[i].toInt() and 0xFF
            val lo = mac[i + 4].toInt() and 0xFF
            sb.append(alphabet[hi and 0x1F])
            sb.append(alphabet[((hi shr 5) shl 2 or (lo and 0x03)) and 0x1F])
        }
        return sb.toString().chunked(4).joinToString("-")
    }

    /** 常数时间比较，避免按字节计时猜出正确前缀 */
    fun inviteMatches(secret: String, candidate: String): Boolean {
        val expected = inviteCode(secret).replace("-", "").toByteArray()
        val actual = candidate.trim().uppercase().replace("-", "").toByteArray()
        if (expected.size != actual.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor actual[i].toInt())
        return diff == 0
    }

    fun newSecret(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return b64(bytes, urlSafe = true)
    }

    /**
     * 加入时用对方的公钥加密 secret，避免在局域网明文里撒主密钥。
     *
     * 局域网抓包在这个 App 的威胁模型里不算主要风险（家人共用 WiFi），
     * 但把「唯一凭据」明文发出去是没必要的暴露 —— RSA-OAEP 两行就能避免。
     */
    fun encryptForPeer(publicKeyOpenSsh: String, secret: String): String? = runCatching {
        val key = SshKeyReader.parsePublicKey(publicKeyOpenSsh) ?: return null
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        b64(cipher.doFinal(secret.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    fun decryptFromPeer(privateKeyPem: String, payloadB64: String): String? = runCatching {
        val key = SshKeyReader.parsePrivateKey(privateKeyPem) ?: return null
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key)
        String(cipher.doFinal(Base64.decode(payloadB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    // ------------------------------------------------------------ 基础件

    fun hmachex(secret: String, data: String): String =
        hmac(secret, data).joinToString("") { "%02x".format(it) }

    fun hmac(secret: String, data: String): ByteArray {
        val key = secret.toByteArray(Charsets.UTF_8)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun b64(bytes: ByteArray, urlSafe: Boolean = false): String =
        if (urlSafe) Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        else Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/**
 * OpenSSH 格式的**解析**。
 *
 * 之前只有编码（生成密钥时用）。P2P 场景必须能把 PEM 读回 Key 对象去签名/验签 ——
 * 对端只给一行 `ssh-rsa AAAA... comment` 文本，而 JSch 的 `KeyPair.load` 只认
 * 它自己的密钥解析流程，对内存字符串不友好，还要额外拖 jsch 依赖。
 *
 * 格式来源：RFC4253（公钥 blob）、OpenSSH PROTOCOL.key（私钥块）。
 */
object SshKeyReader {

    /** 解析 `ssh-rsa <base64> <comment>` */
    fun parsePublicKey(openSsh: String): RSAPublicKey? = runCatching {
        val parts = openSsh.trim().split(Regex("\\s+"))
        if (parts.size < 2 || parts[0] != "ssh-rsa") return null
        val blob = Base64.decode(parts[1], Base64.NO_WRAP)
        val r = BlobReader(blob)
        if (r.readString() != "ssh-rsa") return null
        val e = r.readMpInt()
        val n = r.readMpInt()
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e)) as RSAPublicKey    }.getOrNull()

    /** 解析 `-----BEGIN OPENSSH PRIVATE KEY-----` 块（本 App 自己生成的，无密码无 KDF） */
    fun parsePrivateKey(pem: String): RSAPrivateCrtKey? = runCatching {
        val b64 = pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
        if (b64.isEmpty()) return null
        val r = BlobReader(Base64.decode(b64, Base64.NO_WRAP))
        // magic 是 "openssh-key-v1\0"，恰好 15 字节
        if (r.readBytes(15).toString(Charsets.US_ASCII) != "openssh-key-v1\u0000") return null
        if (r.readString() != "none") return null // 加密私钥不支持：本 App 不生成
        if (r.readString() != "none") return null
        r.readString()  // kdf options
        r.readInt()     // nkeys
        r.readBytesString()  // public key blob，私钥块里还会重复一份
        val priv = BlobReader(r.readBytesString())
        priv.readInt()  // checkint1
        priv.readInt()  // checkint2
        if (priv.readString() != "ssh-rsa") return null
        val n = priv.readMpInt()
        val e = priv.readMpInt()
        val d = priv.readMpInt()
        val iqmp = priv.readMpInt()   // OpenSSH 的 crtCoefficient = q⁻¹ mod p
        val p = priv.readMpInt()
        val q = priv.readMpInt()
        // RSAPrivateCrtKeySpec 需要 8 个参数：dP 和 dQ 得自己算
        val one = BigInteger.ONE
        val dP = d.mod(p.subtract(one))
        val dQ = d.mod(q.subtract(one))
        KeyFactory.getInstance("RSA")
            .generatePrivate(RSAPrivateCrtKeySpec(n, e, d, p, q, dP, dQ, iqmp)) as RSAPrivateCrtKey
    }.getOrNull()

    /** 大端读取器：SSH 协议所有整数都是 mpint、所有字符串都是 int32 长度前缀 */
    private class BlobReader(private val buf: ByteArray) {
        private var pos = 0

        fun readBytes(n: Int): ByteArray {
            require(pos + n <= buf.size) { "blob underflow" }
            val out = buf.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun readInt(): Int {
            val b = readBytes(4)
            return ((b[0].toInt() and 0xFF) shl 24) or
                ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or
                (b[3].toInt() and 0xFF)
        }

        fun readString(): String = readBytesString().toString(Charsets.UTF_8)

        /** 二进制字段（公钥 blob、私钥块）不能用 [readString]，UTF-8 会破坏字节 */
        fun readBytesString(): ByteArray = readBytes(readInt())

        /** 兼容带前导 0x00 的正数编码 */
        fun readMpInt(): BigInteger {
            val bytes = readBytes(readInt())
            return if (bytes.isEmpty()) BigInteger.ZERO else BigInteger(1, bytes)
        }
    }
}
